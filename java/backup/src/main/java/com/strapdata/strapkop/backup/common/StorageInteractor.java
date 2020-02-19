package com.strapdata.strapkop.backup.common;

import com.google.common.base.Strings;

import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class StorageInteractor {

    public final String restoreFromRootDir;
    public final String restoreFromNamespace;
    public final String restoreFromClusterId;
    public final String restoreFromNodeId;
    public final String restoreFromBackupBucket;

    public StorageInteractor(final String restoreFromRootDir,
                             final String restoreFromNamespace,
                             final String restoreFromClusterId,
                             final String restoreFromNodeId,
                             final String restoreFromBackupBucket) {
        this.restoreFromRootDir = restoreFromRootDir;
        this.restoreFromNamespace = restoreFromNamespace;
        this.restoreFromClusterId = restoreFromClusterId;
        this.restoreFromNodeId = restoreFromNodeId;
        this.restoreFromBackupBucket = restoreFromBackupBucket;
    }

    public String resolveRemotePath(final Path objectKey) {
        return Strings.isNullOrEmpty(restoreFromRootDir) ? Paths.get(restoreFromNamespace).resolve(restoreFromClusterId).resolve(restoreFromNodeId).resolve(objectKey).toString() :
                Paths.get(restoreFromRootDir).resolve(restoreFromNamespace).resolve(restoreFromClusterId).resolve(restoreFromNodeId).resolve(objectKey).toString();
    }

    public String resolveTaskDescriptionRemotePath(final String taskName) {
        return Strings.isNullOrEmpty(restoreFromRootDir) ? Paths.get(restoreFromNamespace).resolve(restoreFromClusterId).resolve(Constants.TASK_DESCRIPTION_UPLOAD_DIR).resolve(taskName).toString() :
                Paths.get(restoreFromRootDir).resolve(restoreFromNamespace).resolve(restoreFromClusterId).resolve(Constants.TASK_DESCRIPTION_UPLOAD_DIR).resolve(taskName).toString();
    }

    public abstract RemoteObjectReference objectKeyToRemoteReference(final Path objectKey) throws Exception;

    public abstract RemoteObjectReference taskDescriptionRemoteReference(final String taskName) throws Exception;

}