package com.strapdata.strapkop.controllers;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.strapkop.pipeline.K8sWatchEventData;

import javax.inject.Singleton;
import java.util.EnumSet;

import static com.strapdata.strapkop.pipeline.K8sWatchEventData.Type.*;

@Singleton
public class DataCenterDeletionController
        implements Controller<K8sWatchEventData<DataCenter>> {
    
    private static final EnumSet<K8sWatchEventData.Type> acceptedEventTypes = EnumSet.of(DELETED);

    private final DataCenterActionFactory dataCenterControllerFactory;
    
    public DataCenterDeletionController(final DataCenterActionFactory dataCenterControllerFactory) {
        this.dataCenterControllerFactory = dataCenterControllerFactory;
    }
    
    @Override
    public void accept(K8sWatchEventData<DataCenter> data) throws Exception {
        if (!acceptedEventTypes.contains(data.getType())) {
            return ;
        }
    
        dataCenterControllerFactory.createDeletion(new Key(data.getResource().getMetadata())).deleteDataCenter();
    }
}
