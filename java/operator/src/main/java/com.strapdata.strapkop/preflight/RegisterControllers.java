package com.strapdata.strapkop.preflight;

import com.strapdata.strapkop.controllers.Controller;
import com.strapdata.strapkop.controllers.DataCenterDeletionController;
import com.strapdata.strapkop.controllers.DataCenterReconciliationController;
import com.strapdata.strapkop.pipeline.DataCenterPipeline;
import com.strapdata.strapkop.pipeline.EventPipeline;

import javax.inject.Singleton;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

@Singleton
@SuppressWarnings("rawtypes")
public class RegisterControllers implements Preflight<Void> {
    
    private final Map<Class<? extends Controller>, Controller> controllers = new HashMap<>();
    private final Map<Class<? extends EventPipeline>, EventPipeline> pipelines = new HashMap<>();
    
    public RegisterControllers(Collection<Controller> controllers, Collection<EventPipeline> pipelines) {
        controllers.forEach(controller -> this.controllers.put(controller.getClass(), controller));
        pipelines.forEach(pipeline -> this.pipelines.put(pipeline.getClass(), pipeline));
    }
    
    @Override
    public Void call() throws Exception {
        bind(DataCenterPipeline.class, DataCenterReconciliationController.class);
        bind(DataCenterPipeline.class, DataCenterDeletionController.class);
        return null;
    }
    
    @SuppressWarnings("unchecked")
    private <KeyT, DataT> void bind(Class<? extends EventPipeline<KeyT, DataT>> pipeline,
                                    Class<? extends Controller<DataT>> controller) {
        pipelines.get(pipeline).subscribe(controllers.get(controller));
    }
    
}
