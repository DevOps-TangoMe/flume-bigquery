package com.tango.flume.bigquery.sink;

import com.google.api.services.bigquery.model.TableDataInsertAllRequest;
import org.apache.flume.Event;
import org.apache.flume.conf.Configurable;
import org.apache.flume.conf.ConfigurableComponent;

/**
 * @author Nina Safonova (nsafonova)
 */
public interface IInsertRequestRowsBuilderFactory extends Configurable, ConfigurableComponent {

    TableDataInsertAllRequest.Rows createRows(Event event);

}
