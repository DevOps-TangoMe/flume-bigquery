package com.tango.flume.bigquery.sink;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.conf.ComponentConfiguration;

import com.google.api.services.bigquery.model.TableDataInsertAllRequest;
import com.google.api.services.bigquery.model.TableRow;
import com.google.common.base.Preconditions;

/**
 * @author Nina Safonova (nsafonova)
 */
public class InsertRequestRowsBuilderFactory implements IInsertRequestRowsBuilderFactory {

    private static final String PARAM_ID_HEADER = "idHeader";
    private static final String PARAM_INCLUDE_HEADERS = "includeHeaders";
    private static final String PARAM_EXCLUDE_HEADERS = "excludeHeaders";

    private String idHeaderName;
    private Map<String, String> includeHeadersRenameMap;
    private Set<String> excludeHeaders;

    @Override
    public TableDataInsertAllRequest.Rows createRows(Event event) {
        TableDataInsertAllRequest.Rows result = null;
        if (event != null) {
            TableRow row = new TableRow();
            Map<String, String> headers = new LinkedHashMap<String, String>(event.getHeaders());
            if (!headers.isEmpty()) {
                String id = idHeaderName != null ? headers.get(idHeaderName) : null;
                for (Map.Entry<String, String> headerEntry : headers.entrySet()) {
                    String headerName = headerEntry.getKey();
                    if (!excludeHeaders.isEmpty() && excludeHeaders.contains(headerName)) {
                        continue;
                    }
                    String columnName = includeHeadersRenameMap.get(headerName);
                    if (!excludeHeaders.isEmpty() || columnName != null || includeHeadersRenameMap.isEmpty()) {
                        row = setColumnValue(row, columnName == null ? headerName : columnName, headerEntry.getValue());
                        if (row == null) {
                            break;
                        }
                    }
                }
                if (row != null) {
                    result = new TableDataInsertAllRequest.Rows();
                    if (StringUtils.isNotBlank(id)) {
                        result.setInsertId(id);
                    }
                    result.setJson(row);
                }
            }
        }
        return result;
    }

    protected TableRow setColumnValue(TableRow row, String columnName, String value) {
        return row.set(columnName, value);
    }

    @Override
    public void configure(Context context) {
        this.idHeaderName = context.getString(PARAM_ID_HEADER);
        if (idHeaderName != null) {
            Preconditions.checkState(StringUtils.isNotBlank(idHeaderName), PARAM_ID_HEADER
                    + " must not be blank if defined");
            idHeaderName = idHeaderName.trim();
        }

        this.includeHeadersRenameMap = extractIncludeHeadersRenameMap(context);
        this.excludeHeaders = extractExcludeHeaders(context, this.includeHeadersRenameMap.keySet());
    }

    private Set<String> extractExcludeHeaders(Context context, Set<String> includeHeaders) {
        Set<String> excludeHeaders = null;
        String excludeHeaderNamesList = context.getString(PARAM_EXCLUDE_HEADERS);
        if (excludeHeaderNamesList != null) {
            for (String part : StringUtils.splitPreserveAllTokens(excludeHeaderNamesList, ",")) {
                if (StringUtils.isNotBlank(part)) {
                    String excludeHeaderName = part.trim();
                    Preconditions.checkState(includeHeaders.contains(excludeHeaderName), excludeHeaderName
                            + " is in both exclude and include list");
                    if (excludeHeaders == null) {
                        excludeHeaders = new HashSet<String>();
                    }
                    excludeHeaders.add(excludeHeaderName);
                }
            }
        }
        return excludeHeaders == null ? Collections.<String> emptySet() : excludeHeaders;
    }

    private Map<String, String> extractIncludeHeadersRenameMap(Context context) {
        Map<String, String> includeHeadersRenameMap = null;
        String includeHeaderNamesList = context.getString(PARAM_INCLUDE_HEADERS);
        if (includeHeaderNamesList != null) {
            for (String part : StringUtils.splitPreserveAllTokens(includeHeaderNamesList, ",")) {
                String[] keyValue = StringUtils.splitPreserveAllTokens(part, "=");
                if (keyValue.length > 0) {
                    String originalHeaderName = keyValue[0].trim();
                    if (StringUtils.isNotBlank(originalHeaderName)) {
                        String futureRowColumnName = originalHeaderName;
                        if (keyValue.length > 1) {
                            String columnName = keyValue[1].trim();
                            if (StringUtils.isNotBlank(columnName)) {
                                futureRowColumnName = columnName;
                            }
                        }
                        if (includeHeadersRenameMap == null) {
                            includeHeadersRenameMap = new HashMap<String, String>();
                        }
                        includeHeadersRenameMap.put(originalHeaderName, futureRowColumnName);
                    }
                }
            }
        }
        return includeHeadersRenameMap == null ? Collections.<String, String> emptyMap() : includeHeadersRenameMap;
    }

    @Override
    public void configure(ComponentConfiguration componentConfiguration) {

    }

}
