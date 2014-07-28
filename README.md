flume-bigquery
===========

Google BigQuery sink for Apache Flume


License
-------

This is released under Apache License v2


Prerequisites
-------

Google BigQuery Sink uses the server-to-server OAuth 2.0 flow for authentication so you need to have one to be able to steam data to your project as well as to have the private key dowloaded from the Google Developers console.
More information can be found find here https://developers.google.com/accounts/docs/OAuth2ServiceAccount.


Configuration example
---------------------

Sink configuration example:

    agent.sinks.bigquerySink.type = com.tango.flume.bigquery.sink.GoogleBigQuerySink
    agent.sinks.bigquerySink.serviceAccountId = {service_account_id}
    agent.sinls.bigquerySink.serviceAccountPrivateKeyFromP12File = /path/to/p12/private/key
    agent.sinks.bigquerySink.projectId = {bigquery_projectId}
    agent.sinks.bigquerySink.datasetId = {bigquery_datasetId}
    agent.sinks.bigquerySink.tableId = {bigquery_tableIdId}
    agent.sinks.bigquerySink.batchSize = 100
    agent.sinks.bigquerySink.connectTimeoutMs = 10000
    agent.sinls.bigquerySink.readTimeoutMs = 20000
    agent.sinls.bigquerySink.maxTryCount = -1
    agent.sinks.bigquerySink.rowFactory = com.tango.flume.bigquery.sink.InsertRequestRowsBuilderFactory
    agent.sinks.bigquerySink.rowFactory.idHeader = id
    agent.sinks.bigquerySink.rowFactory.includeHeaders = timestamp=event_time,event_type,event_duration
    agent.sinks.bigquerySink.rowFactory.excludeHeaders =

maxTryCount - if specified value is more than 0 than no more than maxTryCount insert request attempts will be accomplished and if finally failed, than events batch will be skipped.
serviceAccountId - email address for your service account (usually looks like 1234567890@developer.gserviceaccount.com)
serviceAccountPrivateKeyFromP12File - path to private key downloaded from the Google Developers console
projectId, datasetId, tableId - corresponding BigQuery project id, dataset id and table id to stream data to
rowFactory - FQCN of com.tango.flume.bigquery.sink.IInsertRequestRowsBuilderFactory interface implementation that defines the way the BigQuery table row will be composed from the flume event, default is com.tango.flume.bigquery.sink.InsertRequestRowsBuilderFactory

com.tango.flume.bigquery.sink.InsertRequestRowsBuilderFactory has additional configuration abilities:
Optional:
1) idHeader - the name of flume event header which value will be used to assign id to the BigQuery row. If not specified - no id will be assigned.
2) includeHeaders - comma separated list of flume event header names to be used as columns of the BigQuery row. Renaming of columns is supported by specifying source and target name with equal sign delimiter (see sink configuration example).
3) excludeHeaders - comma separated list of flume event header names to not include as columns of the BigQuery row.
If no excludeHeaders is specified, than just listed in includeHeaders property ones will be used to create the BigQuery row.
If excludeHeaders is specified, than all headers except listed in excludeHeaders property will be used to create the BigQuery row.
If nor excludeHeaders nor includeHeaders are specified than all headers will be used to create the BigQuery row.

Building
--------

This project uses maven for building all the artefacts.
You can build it with the following command:
    mvn clean install

This will build the following artefacts:
* flume-bigquery-dist/target/flume-bigquery-1.0.0-SNAPSHOT-dist.tar.gz
  The tarball can be directly unpacked into Apache Flume plugins.d directory

* flume-bigquery-dist/target/rpm/tango-flume-bigquery/RPMS/noarch/tango-flume-bigquery-1.0.0-SNAPSHOT*.noarch.rpm
  This package will install itself on top of Apache Flume package and be ready for use right away.



