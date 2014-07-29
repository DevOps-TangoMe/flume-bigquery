package com.tango.flume.bigquery.sink;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.CounterGroup;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.FlumeException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.conf.ConfigurationException;
import org.apache.flume.instrumentation.SinkCounter;
import org.apache.flume.sink.AbstractSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.bigquery.Bigquery;
import com.google.api.services.bigquery.model.TableDataInsertAllRequest;
import com.google.api.services.bigquery.model.TableDataInsertAllResponse;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;

public class GoogleBigQuerySink extends AbstractSink implements Configurable {

    public static final String SERVICE_ACCOUNT_ID = "serviceAccountId";
    public static final String SERVICE_ACCOUNT_PRIVATE_KEY_FROM_P12_FILE = "serviceAccountPrivateKeyFromP12File";
    public static final String PROJECT_ID = "projectId";
    public static final String DATASET_ID = "datasetId";
    public static final String TABLE_ID = "tableId";
    public static final String BATCH_SIZE = "batchSize";
    public static final String CONNECT_TIMEOUT_MS = "connectTimeoutMs";
    public static final String READ_TIMEOUT_MS = "readTimeoutMs";
    public static final String MAX_TRY_COUNT = "maxTryCount";
    public static final String ROW_FACTORY = "rowFactory";
    public static final String ROW_FACTORY_PREFIX = ROW_FACTORY + ".";

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10000;
    private static final int DEFAULT_MAX_TRY_COUNT = -1;
    private static final int DEFAULT_BATCH_SIZE = 100;

    private static final Logger logger = LoggerFactory.getLogger(GoogleBigQuerySink.class);

    private final CounterGroup counterGroup = new CounterGroup();

    private static final String SCOPE = "https://www.googleapis.com/auth/bigquery";
    private static final HttpTransport TRANSPORT = new NetHttpTransport();
    private static final JsonFactory JSON_FACTORY = new JacksonFactory();

    private int batchSize;
    private String serviceAccountId;
    private String serviceAccountPrivateKeyFromP12File;
    private String projectId;
    private String datasetId;
    private String tableId;

    private int connectTimeoutMs;
    private int readTimeoutMs;
    private int maxTryCount;

    private Bigquery bigquery;
    private SinkCounter sinkCounter;
    private IInsertRequestRowsBuilderFactory insertRequestRowsBuilderFactory;

    @Override
    public Status process() throws EventDeliveryException {
        Status status = Status.READY;
        Channel channel = getChannel();
        Transaction txn = channel.getTransaction();
        try {
            txn.begin();

            List<TableDataInsertAllRequest.Rows> rowList = new ArrayList<TableDataInsertAllRequest.Rows>();

            while (rowList.size() < batchSize) {
                Event event = channel.take();

                if (event == null) {
                    break;
                }

                TableDataInsertAllRequest.Rows insertRequestRows = createRows(event);
                if (insertRequestRows != null) {
                    rowList.add(insertRequestRows);
                }
            }

            int size = rowList.size();
            TableDataInsertAllResponse bulkResponse = null;
            if (size == 0) {
                sinkCounter.incrementBatchEmptyCount();
                counterGroup.incrementAndGet("channel.underflow");
                status = Status.BACKOFF;
            } else {
                if (size < batchSize) {
                    sinkCounter.incrementBatchUnderflowCount();
                    status = Status.BACKOFF;
                } else {
                    sinkCounter.incrementBatchCompleteCount();
                }
                sinkCounter.addToEventDrainAttemptCount(size);
                int count = 0;
                boolean success = false;
                while (!(success || (maxTryCount > 0 && count >= maxTryCount))) {
                    try {
                        TableDataInsertAllRequest content = new TableDataInsertAllRequest().setRows(rowList);
                        bulkResponse = bigquery.tabledata().insertAll(projectId, datasetId, tableId, content).execute();
                        success = true;
                    } catch (IOException ex) {
                        String message = "Insert request invoking exception";
                        if (maxTryCount <= 0) {
                            throw new EventDeliveryException(message, ex);
                        }
                        count++;
                        logger.warn(message, ex);
                    }
                }
                if (bulkResponse == null) {
                    logger.error(String.format("Retries exhausted. Batch with size %d will be skipped", size));
                } else if (bulkResponse.getInsertErrors() != null) {
                    List<TableDataInsertAllResponse.InsertErrors> insertErrors = bulkResponse.getInsertErrors();
                    if (!insertErrors.isEmpty()) {
                        StringBuilder errorMessage = new StringBuilder();
                        for (TableDataInsertAllResponse.InsertErrors insertError : insertErrors) {
                            if (errorMessage.length() != 0) {
                                errorMessage.append("\n");
                            }
                            errorMessage.append(insertError.toPrettyString());
                            if (insertError.getIndex() != null) {
                                int index = insertError.getIndex().intValue();
                                if (rowList.size() > index) {
                                    TableDataInsertAllRequest.Rows row = rowList.get(index);
                                    errorMessage.append(" for row: ").append(JSON_FACTORY.toPrettyString(row));
                                }
                            }
                        }
                        logger.error(String.format("%s errors inserting %s rows: %s", insertErrors.size(), size, errorMessage));
                    }
                }
            }
            txn.commit();
            sinkCounter.addToEventDrainSuccessCount(bulkResponse != null ? size : 0);
            counterGroup.incrementAndGet("transaction.success");
        } catch (Throwable ex) {
            try {
                txn.rollback();
                counterGroup.incrementAndGet("transaction.rollback");
            } catch (Exception ex2) {
                logger.error(
                        "Exception in rollback. Rollback might not have been successful.",
                        ex2);
            }
            String errorMessage = "Failed to commit transaction. Transaction rolled back.";
            logger.error(errorMessage, ex);
            Throwables.propagateIfPossible(ex);
            throw new EventDeliveryException(errorMessage, ex);
        } finally {
            txn.close();
        }
        return status;
    }

    protected TableDataInsertAllRequest.Rows createRows(Event event) {
        TableDataInsertAllRequest.Rows insertRequestRows = null;
        try {
            insertRequestRows = insertRequestRowsBuilderFactory.createRows(event);
            if (logger.isDebugEnabled()) {
                if (insertRequestRows != null) {
                    logger.debug("Row created from event '%s': %s", event, insertRequestRows.toPrettyString());
                } else {
                    logger.debug("No row created from event '%s'", event);
                }
            }
        } catch (Exception ex) {
            logger.warn(String.format("Error creating rows from event '%s': %s. Skipping it.", event, ex.getMessage()), ex);
        }
        return insertRequestRows;
    }

    @Override
    public void configure(Context context) {
        this.serviceAccountId = context.getString(SERVICE_ACCOUNT_ID);
        this.serviceAccountPrivateKeyFromP12File = context.getString(SERVICE_ACCOUNT_PRIVATE_KEY_FROM_P12_FILE);
        this.projectId = context.getString(PROJECT_ID);
        this.datasetId = context.getString(DATASET_ID);
        this.tableId = context.getString(TABLE_ID);

        this.batchSize = context.getInteger(BATCH_SIZE, DEFAULT_BATCH_SIZE);
        this.connectTimeoutMs = context.getInteger(CONNECT_TIMEOUT_MS, DEFAULT_CONNECT_TIMEOUT_MS);
        this.readTimeoutMs = context.getInteger(READ_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
        this.maxTryCount = context.getInteger(MAX_TRY_COUNT, DEFAULT_MAX_TRY_COUNT);

        String insertRequestRowsBuilderFactoryClass = InsertRequestRowsBuilderFactory.class.getName();
        if (StringUtils.isNotBlank(context.getString(ROW_FACTORY))) {
            insertRequestRowsBuilderFactoryClass = context.getString(ROW_FACTORY);
        }

        Context factoryContext = new Context();
        factoryContext.putAll(context.getSubProperties(ROW_FACTORY_PREFIX));

        try {
            @SuppressWarnings("unchecked") Class<? extends Configurable> clazz = (Class<? extends Configurable>) Class
                    .forName(insertRequestRowsBuilderFactoryClass);
            Configurable factory = clazz.newInstance();
            if (factory instanceof IInsertRequestRowsBuilderFactory) {
                insertRequestRowsBuilderFactory = (IInsertRequestRowsBuilderFactory) factory;
            } else {
                throw new IllegalArgumentException(
                        insertRequestRowsBuilderFactoryClass + " is unsupported class for insertRequestRowsBuilderFactory");
            }
            insertRequestRowsBuilderFactory.configure(factoryContext);
        } catch (Exception e) {
            logger.error("Could not instantiate insert request rows builder: " + e.getMessage(), e);
            Throwables.propagate(e);
        }

        if (sinkCounter == null) {
            sinkCounter = new SinkCounter(getName());
        }

        Preconditions.checkState(StringUtils.isNotBlank(serviceAccountId), "Missing Param:" + SERVICE_ACCOUNT_ID);
        Preconditions.checkState(StringUtils.isNotBlank(serviceAccountPrivateKeyFromP12File), "Missing Param:"
                + SERVICE_ACCOUNT_PRIVATE_KEY_FROM_P12_FILE);
        Preconditions.checkState(StringUtils.isNotBlank(datasetId), "Missing Param:" + DATASET_ID);
        Preconditions.checkState(StringUtils.isNotBlank(tableId), "Missing Param:" + TABLE_ID);
        Preconditions.checkState(batchSize > 0, BATCH_SIZE + " must be greater than 0");
        Preconditions.checkState(connectTimeoutMs > 0, CONNECT_TIMEOUT_MS + " must be greater than 0");
        Preconditions.checkState(readTimeoutMs > 0, READ_TIMEOUT_MS + " must be greater than 0");
    }

    @Override
    public void start() {
        sinkCounter.start();
        try {
            openConnection();
        } catch (Exception ex) {
            sinkCounter.incrementConnectionFailedCount();
            closeConnection();
            Throwables.propagateIfPossible(ex);
            throw new FlumeException(ex);
        }
        super.start();
    }

    @Override
    public void stop() {
        closeConnection();
        sinkCounter.stop();
        super.stop();
    }

    protected void openConnection() {
        createClient();
        sinkCounter.incrementConnectionCreatedCount();
    }

    private void createClient() {
        try {
            final GoogleCredential credential = new GoogleCredential.Builder().setTransport(TRANSPORT)
                    .setJsonFactory(JSON_FACTORY)
                    .setServiceAccountId(serviceAccountId)
                    .setServiceAccountScopes(Collections.singleton(SCOPE))
                    .setServiceAccountPrivateKeyFromP12File(new File(serviceAccountPrivateKeyFromP12File))
                    .setRequestInitializer(new HttpRequestInitializer() {
                        @Override
                        public void initialize(HttpRequest httpRequest) throws IOException {
                            httpRequest.setConnectTimeout(connectTimeoutMs);
                            httpRequest.setReadTimeout(readTimeoutMs);
                        }
                    }).build();
            Bigquery.Builder builder = new Bigquery.Builder(TRANSPORT, JSON_FACTORY, credential).setApplicationName("BigQuery-Service-Accounts/0.1");
            bigquery = builder.build();
        } catch (Exception ex) {
            throw new ConfigurationException("Error creating bigquery client: " + ex.getMessage(), ex);
        }
    }

    private void closeConnection() {
        bigquery = null;
        sinkCounter.incrementConnectionClosedCount();
    }

}
