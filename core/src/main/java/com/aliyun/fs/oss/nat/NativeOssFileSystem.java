/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aliyun.fs.oss.nat;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.aliyun.fs.oss.common.*;
import com.aliyun.fs.oss.utils.Utils;
import com.google.common.base.Preconditions;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.retry.RetryPolicies;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.io.retry.RetryProxy;
import org.apache.hadoop.util.Progressable;

public class NativeOssFileSystem extends FileSystem {

    public static final Log LOG =
            LogFactory.getLog(NativeOssFileSystem.class);
    public static final String FOLDER_SUFFIX = "_$folder$";
    public static final long MAX_OSS_FILE_SIZE = 5 * 1024 * 1024 * 1024L;
    public static final String PATH_DELIMITER = Path.SEPARATOR;
    public static final int OSS_MAX_LISTING_LENGTH = 1000;
    public static final String OSSREADER_ALGORITHM_VERSION = "mapreduce.ossreader.algorithm.version";
    public static final int OSSREADER_ALGORITHM_VERSION_DEFAULT = 1;
    private int algorithmVersion;

    public class NativeOssFsInputStream extends FSInputStream {

        BufferReader bufferReader = null;

        public NativeOssFsInputStream(String key) throws IOException {
            this.bufferReader = new BufferReader(store, key, conf, algorithmVersion);
        }

        @Override
        public synchronized int read() throws IOException {
            return bufferReader.read();
        }
        @Override
        public synchronized int read(byte[] b, int off, int len)
                throws IOException {
            return bufferReader.read(b, off, len);
        }

        @Override
        public synchronized void close() throws IOException {
            bufferReader.close();
        }

        @Override
        public synchronized void seek(long newpos) throws IOException {
            bufferReader.seek(newpos);
        }

        @Override
        public synchronized long getPos() throws IOException {
            return bufferReader.getPos();
        }
        @Override
        public boolean seekToNewSource(long targetPos) throws IOException {
            return false;
        }
    }

    private class NativeOssFsOutputStream extends OutputStream {

        private Configuration conf;
        private String key;
        private File blockFile;
        private OutputStream blockOutStream;
        private boolean closed;
        private boolean append;
        private List<File> blockFiles = new ArrayList<File>();
        private long blockSize;
        private Long blockWritten = 0L;
        private int blockId = 0;

        public NativeOssFsOutputStream(Configuration conf, NativeFileSystemStore store, String key,
                                       boolean append, Progressable progress, int bufferSize) throws IOException {
            this.conf = conf;
            this.key = key;
            this.append = append;
            this.blockFile = newBlockFile();
            blockSize = conf.getLong("fs.oss.local.block.size", 128 * 1024 * 1024L);
            LOG.info("OutputStream for key '" + key + "' writing to tempfile '" + this.blockFile + "' for block " + blockId);
            this.blockOutStream = new BufferedOutputStream(new FileOutputStream(blockFile));
        }

        private File newBlockFile() throws IOException {
            File dir = Utils.getTempBufferDir(conf);
            if (!dir.mkdirs() && !dir.exists()) {
                throw new IOException("Cannot create OSS buffer directory: " + dir);
            }
            File result = File.createTempFile("output-", ".data", dir);
            result.deleteOnExit();
            return result;
        }

        @Override
        public synchronized void flush() throws IOException {
            blockOutStream.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }

            blockOutStream.flush();
            blockOutStream.close();
            if (!blockFiles.contains(blockFile)) {
                blockFiles.add(blockFile);
            }
            LOG.info("OutputStream for key '" + key + "' closed. Now beginning upload");

            try {
                store.storeFiles(key, blockFiles, append);
            } finally {
                for(File blockFile: blockFiles) {
                    if (blockFile.exists() && !blockFile.delete()) {
                        LOG.warn("Could not delete temporary OSS file: " + blockFile);
                    }
                }
                super.close();
                closed = true;
            }
            LOG.info("OutputStream for key '" + key + "' upload complete");
        }

        @Override
        public synchronized void write(int b) throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }

            blockOutStream.write(b);
            blockWritten++;
            if (blockWritten >= blockSize) {
                flushData();
                blockWritten = 0L;
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }

            blockOutStream.write(b, off, len);
            blockWritten += len;
            if (blockWritten >= blockSize) {
                flushData();
                blockWritten = 0L;
            }
        }

        private synchronized void flushData() throws IOException {
            blockFiles.add(blockFile);
            blockOutStream.flush();
            blockOutStream.close();
            blockFile = newBlockFile();
            blockId++;
            LOG.info("OutputStream for key '" + key + "' writing to tempfile '" + this.blockFile + "' for block " + blockId);
            blockOutStream = new BufferedOutputStream(new FileOutputStream(blockFile));
        }
    }

    private URI uri;
    private int bufferSize;
    NativeFileSystemStore store;
    private Configuration conf;
    private Path workingDir = new Path(".");

    public NativeOssFileSystem() {
        // set store in initialize()
    }

    public NativeOssFileSystem(NativeFileSystemStore store) {
        this.store = store;
    }

    @Override
    public void initialize(URI uri, Configuration conf) throws IOException {
        super.initialize(uri, conf);
        if (store == null) {
            store = createDefaultStore(conf);
        }
        try {
            store.initialize(uri, conf);
        } catch (Exception e) {
            LOG.warn(e.getMessage());
            throw new IOException(e);
        }
        setConf(conf);
        this.uri = URI.create(uri.getScheme() + "://" + uri.getAuthority());
        this.bufferSize = conf.getInt("fs.oss.readBuffer.size", 64 * 1024 * 1024);
        // do not suggest to use too large buffer in case of GC issue or OOM.
        if (this.bufferSize >= 256 * 1024 * 1024) {
            LOG.warn("'fs.oss.readBuffer.size' is " + bufferSize + ", it's to large and system will suppress it down " +
                    "to '268435456' automatically.");
            this.bufferSize = 256 * 1024 * 1024;
        }
        this.conf = conf;
        this.algorithmVersion = conf.getInt(OSSREADER_ALGORITHM_VERSION, OSSREADER_ALGORITHM_VERSION_DEFAULT);
        if (algorithmVersion != 1 && algorithmVersion != 2) {
            throw new IOException("Only 1 or 2 algorithm version is supported");
        }
    }

    private static NativeFileSystemStore createDefaultStore(Configuration conf) {
        NativeFileSystemStore store = new JetOssNativeFileSystemStore();

        RetryPolicy basePolicy = RetryPolicies.retryUpToMaximumCountWithFixedSleep(
                conf.getInt("fs.oss.maxRetries", 4),
                conf.getLong("fs.oss.sleepTimeSeconds", 10), TimeUnit.SECONDS);
        Map<Class<? extends Exception>, RetryPolicy> exceptionToPolicyMap =
                new HashMap<Class<? extends Exception>, RetryPolicy>();
        // for reflection invoke.
        exceptionToPolicyMap.put(InvocationTargetException.class, basePolicy);
        exceptionToPolicyMap.put(IOException.class, basePolicy);
        exceptionToPolicyMap.put(OssException.class, basePolicy);

        RetryPolicy methodPolicy = RetryPolicies.retryByException(RetryPolicies.TRY_ONCE_THEN_FAIL, exceptionToPolicyMap);
        Map<String, RetryPolicy> methodNameToPolicyMap =
                new HashMap<String, RetryPolicy>();
        methodNameToPolicyMap.put("storeFile", methodPolicy);
        methodNameToPolicyMap.put("storeFiles", methodPolicy);
        methodNameToPolicyMap.put("storeEmptyFile", methodPolicy);
        methodNameToPolicyMap.put("retrieveMetadata", methodPolicy);
        methodNameToPolicyMap.put("retrieve", methodPolicy);
        methodNameToPolicyMap.put("purge", methodPolicy);
        methodNameToPolicyMap.put("dump", methodPolicy);
        methodNameToPolicyMap.put("doesObjectExist", methodPolicy);
        methodNameToPolicyMap.put("copy", methodPolicy);
        methodNameToPolicyMap.put("list", methodPolicy);
        methodNameToPolicyMap.put("delete", methodPolicy);

        return (NativeFileSystemStore) RetryProxy.create(NativeFileSystemStore.class, store, methodNameToPolicyMap);
    }

    private static String pathToKey(Path path) {
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("Path must be absolute: " + path);
        }
        // OSS File Path can not start with "/", so we need to scratch the first "/".
        String absolutePath = path.toUri().getPath();
        return absolutePath.substring(1);
    }

    private static Path keyToPath(String key) {
        return new Path(key);
    }

    private Path makeAbsolute(Path path) {
        // TODO: here need to review
        return path;
    }

    @Override
    public FSDataOutputStream append(Path f, int bufferSize, Progressable progress) throws IOException {
        Path absolutePath = makeAbsolute(f);
        String key = pathToKey(absolutePath);
        return new FSDataOutputStream(new NativeOssFsOutputStream(getConf(), store,
                key, true, progress, bufferSize), statistics);
    }

    @Override
    public FSDataOutputStream create(Path f, FsPermission permission, boolean overwrite, int bufferSize,
                                     short replication, long blockSize, Progressable progress)
            throws IOException {
        if (exists(f) && !overwrite) {
            throw new IOException("File already exists: "+f);
        }
        Path absolutePath = makeAbsolute(f);
        String key = pathToKey(absolutePath);
        return new FSDataOutputStream(new NativeOssFsOutputStream(getConf(), store,
                key, false, progress, bufferSize), statistics);
    }

    @Override
    @Deprecated
    public boolean delete(Path path) throws IOException {
        return delete(path, true);
    }

    @Override
    public boolean delete(Path f, boolean recurse) throws IOException {
        FileStatus status;
        try {
            status = getFileStatus(f);
        } catch (FileNotFoundException e) {
            LOG.debug("Delete called for '" + f + "' but file does not exist, so returning false");
            return false;
        }
        Path absolutePath = makeAbsolute(f);
        String key = pathToKey(absolutePath);
        if (status.isDir()) {
            if (!recurse && listStatus(f).length > 0) {
                throw new IOException("Can not delete " + f + " at is a not empty directory and recurse option is false");
            }

            createParent(f);

            LOG.debug("Deleting directory '" + f  + "'");
            String priorLastKey = null;
            do {
                PartialListing listing = store.list(key, OSS_MAX_LISTING_LENGTH, priorLastKey, true);
                for (FileMetadata file : listing.getFiles()) {
                    store.delete(file.getKey());
                }
                priorLastKey = listing.getPriorLastKey();
            } while (priorLastKey != null);

            try {
                store.delete(key + FOLDER_SUFFIX);
            } catch (FileNotFoundException e) {
                //this is fine, we don't require a marker
            }
        } else {
            LOG.debug("Deleting file '" + f + "'");
            createParent(f);
            store.delete(key);
        }
        return true;
    }

    @Override
    public FileStatus getFileStatus(Path f) throws IOException {
        Path absolutePath = makeAbsolute(f);
        String key = pathToKey(absolutePath);

        if (key.length() == 0) { // root always exists
            return newDirectory(absolutePath);
        }

        LOG.debug("getFileStatus retrieving metadata for key '" + key + "'");
        FileMetadata meta = store.retrieveMetadata(key);
        if (meta != null) {
            LOG.debug("getFileStatus returning 'file' for key '" + key + "'");
            return newFile(meta, absolutePath);
        }

        if (store.retrieveMetadata(key + FOLDER_SUFFIX) != null) {
            LOG.debug("getFileStatus returning 'directory' for key '" + key + "' as '"
                    + key + FOLDER_SUFFIX + "' exists");
            return newDirectory(absolutePath);
        }

        LOG.debug("getFileStatus listing key '" + key + "'");
        PartialListing listing = store.list(key, 1);
        if (listing.getFiles().length > 0 ||
                listing.getCommonPrefixes().length > 0) {
            LOG.debug("getFileStatus returning 'directory' for key '" + key + "' as it has contents");
            return newDirectory(absolutePath);
        }

        LOG.debug("getFileStatus could not find key '" + key + "'");
        throw new FileNotFoundException("No such file or directory '" + absolutePath + "'");
    }

    @Override
    public String getScheme() {
        return "oss";
    }

    @Override
    public URI getUri() {
        return uri;
    }

    /**
     * <p>
     * If <code>f</code> is a file, this method will make a single call to Oss.
     * If <code>f</code> is a directory, this method will make a maximum of
     * (<i>n</i> / 1000) + 2 calls to Oss, where <i>n</i> is the total number of
     * files and directories contained directly in <code>f</code>.
     * </p>
     */
    @Override
    public FileStatus[] listStatus(Path f) throws IOException {

        Path absolutePath = makeAbsolute(f);
        String key = pathToKey(absolutePath);

        if (key.length() > 0) {
            FileMetadata meta = store.retrieveMetadata(key);
            if (meta != null) {
                return new FileStatus[] { newFile(meta, absolutePath) };
            }
        }

        URI pathUri = absolutePath.toUri();
        Set<FileStatus> status = new TreeSet<FileStatus>();
        String priorLastKey = null;
        do {
            PartialListing listing = store.list(key, OSS_MAX_LISTING_LENGTH, priorLastKey, false);
            for (FileMetadata fileMetadata : listing.getFiles()) {
                Path subPath = keyToPath(fileMetadata.getKey());
                String relativePath = pathUri.relativize(subPath.toUri()).getPath();

                if (fileMetadata.getKey().equals(key + "/")) {
                    // this is just the directory we have been asked to list
                } else if (relativePath.endsWith(FOLDER_SUFFIX)) {
                    status.add(newDirectory(new Path("/" +
                            relativePath.substring(0, relativePath.indexOf(FOLDER_SUFFIX)))));
                } else {
                    // Here, we need to convert "file/path" to "/file/path". Otherwise, Path.makeQualified will
                    // throw `URISyntaxException`.
                    Path modifiedPath = new Path("/" + subPath.toString());
                    status.add(newFile(fileMetadata, modifiedPath));
                }
            }
            for (String commonPrefix : listing.getCommonPrefixes()) {
                Path subPath = keyToPath(commonPrefix);
                String relativePath = pathUri.relativize(subPath.toUri()).getPath();
                status.add(newDirectory(new Path("/" + relativePath)));
            }
            priorLastKey = listing.getPriorLastKey();
        } while (priorLastKey != null);

        if (status.isEmpty()) {
            return new FileStatus[0];
        }

        return status.toArray(new FileStatus[status.size()]);
    }

    private FileStatus newFile(FileMetadata meta, Path path) {
        return new FileStatus(meta.getLength(), false, 1, MAX_OSS_FILE_SIZE,
                meta.getLastModified(), path.makeQualified(this));
    }

    private FileStatus newDirectory(Path path) {
        return new FileStatus(0, true, 1, MAX_OSS_FILE_SIZE, 0,
                path.makeQualified(this));
    }

    @Override
    public boolean mkdirs(Path f, FsPermission permission) throws IOException {
        Path absolutePath = makeAbsolute(f);
        List<Path> paths = new ArrayList<Path>();
        do {
            paths.add(0, absolutePath);
            absolutePath = absolutePath.getParent();
        } while (absolutePath != null);

        boolean result = true;
        for (Path path : paths) {
            result &= mkdir(path);
        }
        return result;
    }

    public boolean mkdir(Path f) throws IOException {
        try {
            FileStatus fileStatus = getFileStatus(f);
            if (!fileStatus.isDir()) {
                throw new IOException(String.format(
                        "Can't make directory for path '%s' since it is a file.", f));
            }
        } catch (FileNotFoundException e) {
            LOG.debug("Making dir '" + f + "' in OSS");
            String key = pathToKey(f);
            store.storeEmptyFile(key + PATH_DELIMITER);
        }
        return true;
    }

    @Override
    public FSDataInputStream open(Path f, int bufferSize) throws IOException {
        FileStatus fs = getFileStatus(f); // will throw if the file doesn't exist
        if (fs.isDir()) {
            throw new IOException("'" + f + "' is a directory");
        }
        LOG.info("Opening '" + f + "' for reading");
        Path absolutePath = makeAbsolute(f);
        String key = pathToKey(absolutePath);
        return new FSDataInputStream(new BufferedFSInputStream(new NativeOssFsInputStream(key), bufferSize));
    }

    // rename() and delete() use this method to ensure that the parent directory
    // of the source does not vanish.
    private void createParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            String key = pathToKey(makeAbsolute(parent));
            if (key.length() > 0) {
                store.storeEmptyFile(key + PATH_DELIMITER);
            }
        }
    }


    @Override
    public boolean rename(Path src, Path dst) throws IOException {

        String srcKey = pathToKey(makeAbsolute(src));

        if (srcKey.length() == 0) {
            // Cannot rename root of file system
            return false;
        }

        final String debugPreamble = "Renaming '" + src + "' to '" + dst + "' - ";

        // Figure out the final destination
        String dstKey;
        try {
            boolean dstIsFile = !getFileStatus(dst).isDir();
            if (dstIsFile) {
                LOG.debug(debugPreamble + "returning false as dst is an already existing file");
                return false;
            } else {
                LOG.debug(debugPreamble + "using dst as output directory");
                dstKey = pathToKey(makeAbsolute(new Path(dst, src.getName())));
            }
        } catch (FileNotFoundException e) {
            LOG.debug(debugPreamble + "using dst as output destination");
            dstKey = pathToKey(makeAbsolute(dst));
            try {
                if (!getFileStatus(dst.getParent()).isDir()) {
                    LOG.debug(debugPreamble + "returning false as dst parent exists and is a file");
                    return false;
                }
            } catch (FileNotFoundException ex) {
                LOG.debug(debugPreamble + "returning false as dst parent does not exist");
                return false;
            }
        }

        boolean srcIsFile;
        try {
            srcIsFile = !getFileStatus(src).isDir();
        } catch (FileNotFoundException e) {
            LOG.debug(debugPreamble + "returning false as src does not exist");
            return false;
        }
        if (srcIsFile) {
            LOG.debug(debugPreamble + "src is file, so doing copy then delete in Oss");
            store.copy(srcKey, dstKey);
            store.delete(srcKey);
        } else {
            LOG.debug(debugPreamble + "src is directory, so copying contents");
            store.storeEmptyFile(dstKey + PATH_DELIMITER);

            List<String> keysToDelete = new ArrayList<String>();
            String priorLastKey = null;
            do {
                PartialListing listing = store.list(srcKey, OSS_MAX_LISTING_LENGTH, priorLastKey, true);
                for (FileMetadata file : listing.getFiles()) {
                    keysToDelete.add(file.getKey());
                    store.copy(file.getKey(), dstKey + file.getKey().substring(srcKey.length()));
                }
                priorLastKey = listing.getPriorLastKey();
            } while (priorLastKey != null);

            LOG.debug(debugPreamble + "all files in src copied, now removing src files");
            for (String key: keysToDelete) {
                store.delete(key);
            }

            try {
                store.delete(srcKey + FOLDER_SUFFIX);
            } catch (FileNotFoundException e) {
                //this is fine, we don't require a marker
            }
            LOG.debug(debugPreamble + "done");
        }

        return true;
    }

    /**
     * Set the working directory to the given directory.
     */
    @Override
    public void setWorkingDirectory(Path newDir) {
        workingDir = newDir;
    }

    @Override
    public Path getWorkingDirectory() {
        return workingDir;
    }
}
