package com.strapdata.backup.uploader;

import com.strapdata.backup.common.Constants;
import com.strapdata.model.backup.BackupArguments;
import com.strapdata.backup.common.AzureRemoteObjectReference;
import com.strapdata.backup.common.RemoteObjectReference;
import com.microsoft.azure.storage.StorageException;
import com.microsoft.azure.storage.blob.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.EnumSet;

public class AzureSnapshotUploader extends SnapshotUploader {
    private static final Logger logger = LoggerFactory.getLogger(AzureSnapshotUploader.class);

    private static final String DATE_TIME_METADATA_KEY = "LastFreshened";

    private final CloudBlobContainer blobContainer;

    public AzureSnapshotUploader(final CloudBlobClient cloudBlobClient,
                                 final BackupArguments arguments,
                                 final String rootBackupDir) throws URISyntaxException, StorageException {
        super(rootBackupDir, arguments.clusterId, arguments.backupId, arguments.backupBucket);

        //Currently just use clusterId (name) as container reference
        this.blobContainer = cloudBlobClient.getContainerReference(restoreFromBackupBucket);
    }

    @Override
    public RemoteObjectReference objectKeyToRemoteReference(final Path objectKey) throws Exception {
        String canonicalPath = resolveRemotePath(objectKey); //TODO move to RemoteObject implementations
        return new AzureRemoteObjectReference(objectKey, canonicalPath, this.blobContainer.getBlockBlobReference(canonicalPath));
    }

    @Override
    public RemoteObjectReference taskDescriptionRemoteReference(String taskName) throws Exception {
        final String path = resolveTaskDescriptionRemotePath(taskName);
        return new AzureRemoteObjectReference(Paths.get(Constants.TASK_DESCRIPTION_DOWNLOAD_DIR).resolve(taskName), path, blobContainer.getBlockBlobReference(path));
    }
    @Override
    public FreshenResult freshenRemoteObject(final RemoteObjectReference object) throws Exception {
        final CloudBlockBlob blob = ((AzureRemoteObjectReference) object).blob;

        final Instant now = Instant.now();

        try {
            blob.getMetadata().put(DATE_TIME_METADATA_KEY, now.toString());
            blob.uploadMetadata();

            return FreshenResult.FRESHENED;

        } catch (final StorageException e) {
            if (e.getHttpStatusCode() != 404)
                throw e;

            return FreshenResult.UPLOAD_REQUIRED;
        }
    }

    @Override
    public void uploadSnapshotFile(final long size, final InputStream localFileStream, final RemoteObjectReference object) throws Exception {
        final CloudBlockBlob blob = ((AzureRemoteObjectReference) object).blob;

        blob.upload(localFileStream, size);
    }

    @Override
    void cleanup() throws Exception {
        deleteStaleBlobs();
    }

    private void deleteStaleBlobs() throws StorageException, URISyntaxException {
        final Date expiryDate = Date.from(ZonedDateTime.now().minusWeeks(1).toInstant());

        final CloudBlobDirectory directoryReference = blobContainer.getDirectoryReference(restoreFromClusterId);

        for (final ListBlobItem blob : directoryReference.listBlobs(null, true, EnumSet.noneOf(BlobListingDetails.class), null, null)) {
            if (!(blob instanceof CloudBlob))
                continue;

            final BlobProperties properties = ((CloudBlob) blob).getProperties();
            if (properties == null || properties.getLastModified() == null)
                continue;

            if (properties.getLastModified().before(expiryDate))
                ((CloudBlob) blob).delete();
        }
    }
}
