package com.strapdata.strapkop.cache;

import com.strapdata.model.Key;
import com.strapdata.model.k8s.cassandra.DataCenter;
import com.strapdata.model.k8s.cassandra.DataCenterStatus;
import com.strapdata.strapkop.event.ElassandraPod;

import javax.inject.Singleton;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Singleton
public class DataCenterCache extends Cache<Key, DataCenter> {
    
    /**
     * Collect a list of ElassandraPod using the datacenter cache, specifically the podStatuses section
     */
    public Iterable<ElassandraPod> listPods() {
        
        return this.values().stream()
                // for each dc, get a stream of pods, and flat map everything
                .flatMap(dataCenter ->
                        // filter out the dc with empty status
                        Optional.ofNullable(dataCenter.getStatus())
                                .map(DataCenterStatus::getPodStatuses)
                                // map each dc to a stream of ElassandraPods
                                .map(podStatuses -> podStatuses.stream()
                                        .map(podStatus -> new ElassandraPod(dataCenter, podStatus.getPodName())))
                                // filtered dc will got an empty stream instead
                                .orElseGet(Stream::empty)
                ).collect(Collectors.toList());
    }
}
