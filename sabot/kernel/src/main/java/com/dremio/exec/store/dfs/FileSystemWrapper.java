/*
 * Copyright (C) 2017 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.dfs;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import org.apache.hadoop.classification.InterfaceAudience.LimitedPrivate;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.ContentSummary;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSError;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileChecksum;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FsServerDefaults;
import org.apache.hadoop.fs.FsStatus;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Options.ChecksumOpt;
import org.apache.hadoop.fs.ParentNotDirectoryException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.UnsupportedFileSystemException;
import org.apache.hadoop.fs.XAttrSetFlag;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclStatus;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.util.Progressable;

import com.dremio.exec.util.AssertionUtil;
import com.dremio.sabot.exec.context.OperatorStats;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

/**
 * FileSystemWrapper is the wrapper around the actual FileSystem implementation.
 *
 * If {@link com.dremio.sabot.exec.context.OperatorStats} are provided it returns an instrumented FSDataInputStream to
 * measure IO wait time and tracking file open/close operations.
 */
public class FileSystemWrapper extends FileSystem implements OpenFileTracker, PathCanonicalizer {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FileSystemWrapper.class);
  private final static boolean TRACKING_ENABLED = AssertionUtil.isAssertionsEnabled();

  public static final String HIDDEN_FILE_PREFIX = "_";
  public static final String DOT_FILE_PREFIX = ".";
  public static final String MAPRFS_SCHEME = "maprfs";

  private final ConcurrentMap<FSDataInputStream, DebugStackTrace> openedFiles = Maps.newConcurrentMap();

  private final FileSystem underlyingFs;
  private final OperatorStats operatorStats;
  private final CompressionCodecFactory codecFactory;
  private final boolean isPdfs;
  private final boolean isMapRfs;

  public FileSystemWrapper(Configuration fsConf) throws IOException {
    this(fsConf, (OperatorStats) null);
  }

  public FileSystemWrapper(Configuration fsConf, OperatorStats operatorStats) throws IOException {
    this(fsConf, FileSystem.get(fsConf), operatorStats);
  }

  public FileSystemWrapper(Configuration fsConf, FileSystem fs) throws IOException {
    this(fsConf, fs, null);
  }

  public FileSystemWrapper(Configuration fsConf, FileSystem fs, OperatorStats operatorStats) {
    this.underlyingFs = fs;
    this.codecFactory = new CompressionCodecFactory(fsConf);
    this.operatorStats = operatorStats;
    this.isPdfs = (underlyingFs instanceof PathCanonicalizer); // only pdfs implements PathCanonicalizer
    this.isMapRfs = isMapRfs(underlyingFs);
  }

  private static boolean isMapRfs(FileSystem fs) {
    try {
      return MAPRFS_SCHEME.equals(fs.getScheme().toLowerCase());
    } catch (UnsupportedOperationException e) {
    }
    return false;
  }

  public static FileSystemWrapper get(Configuration fsConf) throws IOException {
    return new FileSystemWrapper(fsConf);
  }

  public static FileSystemWrapper get(URI uri, Configuration fsConf) throws IOException {
    FileSystem fs = FileSystem.get(uri, fsConf);
    return new FileSystemWrapper(fsConf, fs);
  }

  public static FileSystemWrapper get(Path path, Configuration fsConf) throws IOException {
    FileSystem fs = path.getFileSystem(fsConf);
    return new FileSystemWrapper(fsConf, fs);
  }


  public static FileSystem get(Path path, Configuration fsConf, OperatorStats stats) throws IOException {
    FileSystem fs = path.getFileSystem(fsConf);
    return new FileSystemWrapper(fsConf, fs, stats);
  }

  @Override
  public void setConf(Configuration conf) {
    // Guard against setConf(null) call that is called as part of superclass constructor (Configured) of the
    // FileSystemWrapper, at which point underlyingFs is null.
    if (conf != null && underlyingFs != null) {
      underlyingFs.setConf(conf);
    }
  }

  @Override
  public Configuration getConf() {
    return underlyingFs.getConf();
  }

  /**
   * If OperatorStats are provided return a instrumented {@link org.apache.hadoop.fs.FSDataInputStream}.
   */
  @Override
  public FSDataInputStream open(Path f, int bufferSize) throws IOException {
    try {
      return newFSDataInputStreamWrapper(f, underlyingFs.open(f, bufferSize));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  /**
   * If OperatorStats are provided return a instrumented {@link org.apache.hadoop.fs.FSDataInputStream}.
   */
  @Override
  public FSDataInputStream open(Path f) throws IOException {
    try {
      return newFSDataInputStreamWrapper(f, underlyingFs.open(f));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void initialize(URI name, Configuration conf) throws IOException {
    try {
      underlyingFs.initialize(name, conf);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public String getScheme() {
    return underlyingFs.getScheme();
  }

  @Override
  public FSDataOutputStream create(Path f) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream create(Path f, boolean overwrite) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f, overwrite));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream create(Path f, Progressable progress) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f, progress));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream create(Path f, short replication) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f, replication));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream create(Path f, short replication, Progressable progress) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f, replication, progress));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream create(Path f, boolean overwrite, int bufferSize) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f, overwrite, bufferSize));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream create(Path f, boolean overwrite, int bufferSize, Progressable progress) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f, overwrite, bufferSize, progress));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream create(Path f, boolean overwrite, int bufferSize, short replication,
      long blockSize) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f, overwrite, bufferSize, replication, blockSize));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream create(Path f, boolean overwrite, int bufferSize, short replication, long blockSize, Progressable progress) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f, overwrite, bufferSize, replication, blockSize, progress));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FileStatus getFileStatus(Path f) throws IOException {
    try {
      return underlyingFs.getFileStatus(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  /**
   * Attempt to retrieve the status for the file at the designated path. If it doesn't exist, return an empty value.
   * @param f Path to access
   * @return Optional.absent() or a Optional<FileStatus>.of(FileStatus)
   * @throws IOException
   */
  public Optional<FileStatus> getFileStatusSafe(Path f) throws IOException {
    try {
      return Optional.fromNullable(underlyingFs.getFileStatus(f));
    } catch(FileNotFoundException e) {
      return Optional.<FileStatus>absent();
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void createSymlink(Path target, Path link, boolean createParent) throws AccessControlException, FileAlreadyExistsException, FileNotFoundException, ParentNotDirectoryException, UnsupportedFileSystemException, IOException {
    try {
      underlyingFs.createSymlink(target, link, createParent);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FileStatus getFileLinkStatus(Path f) throws AccessControlException, FileNotFoundException,
      UnsupportedFileSystemException, IOException {
    try {
      return underlyingFs.getFileLinkStatus(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean supportsSymlinks() {
    return underlyingFs.supportsSymlinks();
  }

  @Override
  public Path getLinkTarget(Path f) throws IOException {
    try {
      return underlyingFs.getLinkTarget(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FileChecksum getFileChecksum(Path f) throws IOException {
    try {
      return underlyingFs.getFileChecksum(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void setVerifyChecksum(boolean verifyChecksum) {
    underlyingFs.setVerifyChecksum(verifyChecksum);
  }

  @Override
  public void setWriteChecksum(boolean writeChecksum) {
    underlyingFs.setWriteChecksum(writeChecksum);
  }

  @Override
  public FsStatus getStatus() throws IOException {
    try {
      return underlyingFs.getStatus();
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FsStatus getStatus(Path p) throws IOException {
    try {
      return underlyingFs.getStatus(p);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void setPermission(Path p, FsPermission permission) throws IOException {
    try {
      underlyingFs.setPermission(p, permission);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void setOwner(Path p, String username, String groupname) throws IOException {
    try {
      underlyingFs.setOwner(p, username, groupname);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void setTimes(Path p, long mtime, long atime) throws IOException {
    try {
      underlyingFs.setTimes(p, mtime, atime);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public Path createSnapshot(Path path, String snapshotName) throws IOException {
    try {
      return underlyingFs.createSnapshot(path, snapshotName);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void renameSnapshot(Path path, String snapshotOldName, String snapshotNewName) throws IOException {
    try {
      underlyingFs.renameSnapshot(path, snapshotOldName, snapshotNewName);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void deleteSnapshot(Path path, String snapshotName) throws IOException {
    try {
      underlyingFs.deleteSnapshot(path, snapshotName);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void modifyAclEntries(Path path, List<AclEntry> aclSpec) throws IOException {
    try {
      underlyingFs.modifyAclEntries(path, aclSpec);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void removeAclEntries(Path path, List<AclEntry> aclSpec) throws IOException {
    try {
      underlyingFs.removeAclEntries(path, aclSpec);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void removeDefaultAcl(Path path) throws IOException {
    try {
      underlyingFs.removeDefaultAcl(path);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void removeAcl(Path path) throws IOException {
    try {
      underlyingFs.removeAcl(path);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void setAcl(Path path, List<AclEntry> aclSpec) throws IOException {
    try {
      underlyingFs.setAcl(path, aclSpec);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public AclStatus getAclStatus(Path path) throws IOException {
    try {
      return underlyingFs.getAclStatus(path);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public Path getWorkingDirectory() {
    return underlyingFs.getWorkingDirectory();
  }

  @Override
  public FSDataOutputStream append(Path f, int bufferSize, Progressable progress) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.append(f, bufferSize, progress));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void concat(Path trg, Path[] psrcs) throws IOException {
    try {
      underlyingFs.concat(trg, psrcs);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @Deprecated
  public short getReplication(Path src) throws IOException {
    try {
      return underlyingFs.getReplication(src);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean setReplication(Path src, short replication) throws IOException {
    try {
      return underlyingFs.setReplication(src, replication);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean mkdirs(Path f, FsPermission permission) throws IOException {
    try {
      return underlyingFs.mkdirs(f, permission);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void copyFromLocalFile(Path src, Path dst) throws IOException {
    try {
      underlyingFs.copyFromLocalFile(src, dst);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void moveFromLocalFile(Path[] srcs, Path dst) throws IOException {
    try {
      underlyingFs.moveFromLocalFile(srcs, dst);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void moveFromLocalFile(Path src, Path dst) throws IOException {
    try {
      underlyingFs.moveFromLocalFile(src, dst);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void copyFromLocalFile(boolean delSrc, Path src, Path dst) throws IOException {
    try {
      underlyingFs.copyFromLocalFile(delSrc, src, dst);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void copyFromLocalFile(boolean delSrc, boolean overwrite, Path[] srcs, Path dst) throws IOException {
    try {
      underlyingFs.copyFromLocalFile(delSrc, overwrite, srcs, dst);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void copyFromLocalFile(boolean delSrc, boolean overwrite, Path src, Path dst) throws IOException {
    try {
      underlyingFs.copyFromLocalFile(delSrc, overwrite, src, dst);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void copyToLocalFile(Path src, Path dst) throws IOException {
    try {
      underlyingFs.copyToLocalFile(src, dst);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void moveToLocalFile(Path src, Path dst) throws IOException {
    try {
      underlyingFs.moveToLocalFile(src, dst);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void copyToLocalFile(boolean delSrc, Path src, Path dst) throws IOException {
    try {
      underlyingFs.copyToLocalFile(delSrc, src, dst);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void copyToLocalFile(boolean delSrc, Path src, Path dst, boolean useRawLocalFileSystem) throws IOException {
    try {
      underlyingFs.copyToLocalFile(delSrc, src, dst, useRawLocalFileSystem);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public Path startLocalOutput(Path fsOutputFile, Path tmpLocalFile) throws IOException {
    try {
      return underlyingFs.startLocalOutput(fsOutputFile, tmpLocalFile);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void completeLocalOutput(Path fsOutputFile, Path tmpLocalFile) throws IOException {
    try {
      underlyingFs.completeLocalOutput(fsOutputFile, tmpLocalFile);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void close() throws IOException {
    if (TRACKING_ENABLED) {
      if (openedFiles.size() != 0) {
        final StringBuffer errMsgBuilder = new StringBuffer();

        errMsgBuilder.append(String.format("Not all files opened using this FileSystem are closed. " + "There are" +
            " still [%d] files open.\n", openedFiles.size()));

        for (DebugStackTrace stackTrace : openedFiles.values()) {
          stackTrace.addToStringBuilder(errMsgBuilder);
        }

        final String errMsg = errMsgBuilder.toString();
        logger.error(errMsg);
        throw new IllegalStateException(errMsg);
      }
    }
  }

  @Override
  public long getUsed() throws IOException {
    try {
      return underlyingFs.getUsed();
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @Deprecated
  public long getBlockSize(Path f) throws IOException {
    try {
      return underlyingFs.getBlockSize(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @Deprecated
  public long getDefaultBlockSize() {
    return underlyingFs.getDefaultBlockSize();

  }

  @Override
  public long getDefaultBlockSize(Path f) {
    return underlyingFs.getDefaultBlockSize(f);
  }

  @Override
  @Deprecated
  public short getDefaultReplication() {
    return underlyingFs.getDefaultReplication();
  }

  @Override
  public short getDefaultReplication(Path path) {
    return underlyingFs.getDefaultReplication(path);
  }

  @Override
  public boolean mkdirs(Path folderPath) throws IOException {
    try {
      if (!underlyingFs.exists(folderPath)) {
        return underlyingFs.mkdirs(folderPath);
      } else if (!underlyingFs.getFileStatus(folderPath).isDirectory()) {
        throw new IOException("The specified folder path exists and is not a folder.");
      }
      return false;
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream create(Path f, FsPermission permission, EnumSet<CreateFlag> flags, int bufferSize,
      short replication, long blockSize, Progressable progress, ChecksumOpt checksumOpt) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f, permission, flags, bufferSize, replication,
          blockSize, progress, checksumOpt));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @Deprecated
  public FSDataOutputStream createNonRecursive(Path f, boolean overwrite, int bufferSize, short replication,
      long blockSize, Progressable progress) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.createNonRecursive(f, overwrite, bufferSize, replication,
          blockSize, progress));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @Deprecated
  public FSDataOutputStream createNonRecursive(Path f, FsPermission permission, boolean overwrite, int bufferSize,
      short replication, long blockSize, Progressable progress) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.createNonRecursive(f, permission, overwrite, bufferSize, replication,
          blockSize, progress));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @Deprecated
  public FSDataOutputStream createNonRecursive(Path f, FsPermission permission, EnumSet<CreateFlag> flags, int bufferSize, short replication, long blockSize, Progressable progress) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.createNonRecursive(f, permission, flags, bufferSize, replication, blockSize, progress));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean createNewFile(Path f) throws IOException {
    try {
      return underlyingFs.createNewFile(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream append(Path f) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.append(f));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream append(Path f, int bufferSize) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.append(f, bufferSize));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream create(Path f, FsPermission permission, boolean overwrite, int bufferSize, short
      replication, long blockSize, Progressable progress) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f, permission, overwrite, bufferSize, replication, blockSize, progress));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FSDataOutputStream create(Path f, FsPermission permission, EnumSet<CreateFlag> flags, int bufferSize,
      short replication, long blockSize, Progressable progress) throws IOException {
    try {
      return newFSDataOutputStreamWrapper(underlyingFs.create(f, permission, flags, bufferSize, replication, blockSize, progress));
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FileStatus[] listStatus(Path f) throws FileNotFoundException, IOException {
    try {
      return underlyingFs.listStatus(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public RemoteIterator<Path> listCorruptFileBlocks(Path path) throws IOException {
    try {
      return underlyingFs.listCorruptFileBlocks(path);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FileStatus[] listStatus(Path f, PathFilter filter) throws FileNotFoundException, IOException {
    try {
      return underlyingFs.listStatus(f, filter);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FileStatus[] listStatus(Path[] files) throws FileNotFoundException, IOException {
    try {
      return underlyingFs.listStatus(files);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FileStatus[] listStatus(Path[] files, PathFilter filter) throws FileNotFoundException, IOException {
    try {
      return underlyingFs.listStatus(files, filter);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FileStatus[] globStatus(Path pathPattern) throws IOException {
    try {
      return underlyingFs.globStatus(pathPattern);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FileStatus[] globStatus(Path pathPattern, PathFilter filter) throws IOException {
    try {
      return underlyingFs.globStatus(pathPattern, filter);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public RemoteIterator<LocatedFileStatus> listLocatedStatus(Path f) throws FileNotFoundException, IOException {
    try {
      return underlyingFs.listLocatedStatus(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public RemoteIterator<LocatedFileStatus> listFiles(Path f, boolean recursive) throws FileNotFoundException, IOException {
    try {
      return underlyingFs.listFiles(f, recursive);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public Path getHomeDirectory() {
    return underlyingFs.getHomeDirectory();
  }

  @Override
  public void setWorkingDirectory(Path new_dir) {
    underlyingFs.setWorkingDirectory(new_dir);
  }

  @Override
  public boolean rename(Path src, Path dst) throws IOException {
    try {
      return underlyingFs.rename(src, dst);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @Deprecated
  public boolean delete(Path f) throws IOException {
    try {
      return underlyingFs.delete(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean delete(Path f, boolean recursive) throws IOException {
    try {
      return underlyingFs.delete(f, recursive);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean deleteOnExit(Path f) throws IOException {
    try {
      return underlyingFs.deleteOnExit(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean cancelDeleteOnExit(Path f) {
    return underlyingFs.cancelDeleteOnExit(f);
  }

  @Override
  public boolean exists(Path f) throws IOException {
    try {
      return underlyingFs.exists(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean isDirectory(Path f) throws IOException {
    try {
      return underlyingFs.isDirectory(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean isFile(Path f) throws IOException {
    try {
      return underlyingFs.isFile(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @Deprecated
  public long getLength(Path f) throws IOException {
    try {
      return underlyingFs.getLength(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public ContentSummary getContentSummary(Path f) throws IOException {
    try {
      return underlyingFs.getContentSummary(f);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public URI getUri() {
    return underlyingFs.getUri();
  }

  @Override
  @LimitedPrivate({"HDFS", "MapReduce"})
  public String getCanonicalServiceName() {
    return underlyingFs.getCanonicalServiceName();
  }

  @Override
  @Deprecated
  public String getName() {
    return underlyingFs.getName();
  }

  @Override
  public Path makeQualified(Path path) {
    return underlyingFs.makeQualified(path);
  }

  @Override
  @Private
  public Token<?> getDelegationToken(String renewer) throws IOException {
    try {
      return underlyingFs.getDelegationToken(renewer);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @LimitedPrivate({"HDFS", "MapReduce"})
  public Token<?>[] addDelegationTokens(String renewer, Credentials credentials) throws IOException {
    try {
      return underlyingFs.addDelegationTokens(renewer, credentials);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @LimitedPrivate({"HDFS"})
  @VisibleForTesting
  public FileSystem[] getChildFileSystems() {
    return underlyingFs.getChildFileSystems();
  }

  @Override
  public BlockLocation[] getFileBlockLocations(FileStatus file, long start, long len) throws IOException {
    try {
      return underlyingFs.getFileBlockLocations(file, start, len);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public BlockLocation[] getFileBlockLocations(Path p, long start, long len) throws IOException {
    try {
      return underlyingFs.getFileBlockLocations(p, start, len);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  @Deprecated
  public FsServerDefaults getServerDefaults() throws IOException {
    try {
      return underlyingFs.getServerDefaults();
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FsServerDefaults getServerDefaults(Path p) throws IOException {
    try {
      return underlyingFs.getServerDefaults(p);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public Path resolvePath(Path p) throws IOException {
    try {
      return underlyingFs.resolvePath(p);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public boolean truncate(final Path f, final long newLength) throws IOException {
    try {
      return underlyingFs.truncate(f, newLength);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public RemoteIterator<FileStatus> listStatusIterator(final Path p) throws FileNotFoundException, IOException {
    try {
      return underlyingFs.listStatusIterator(p);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void access(final Path path, final FsAction mode) throws AccessControlException, FileNotFoundException, IOException {
    try {
      underlyingFs.access(path, mode);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public FileChecksum getFileChecksum(final Path f, final long length) throws IOException {
    try {
      return underlyingFs.getFileChecksum(f, length);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void setXAttr(final Path path, final String name, final byte[] value) throws IOException {
    try {
      underlyingFs.setXAttr(path, name, value);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void setXAttr(final Path path, final String name, final byte[] value, final EnumSet<XAttrSetFlag> flag) throws IOException {
    try {
      underlyingFs.setXAttr(path, name, value, flag);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public byte[] getXAttr(final Path path, final String name) throws IOException {
    try {
      return underlyingFs.getXAttr(path, name);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public Map<String, byte[]> getXAttrs(final Path path) throws IOException {
    try {
      return underlyingFs.getXAttrs(path);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public Map<String, byte[]> getXAttrs(final Path path, final List<String> names) throws IOException {
    try {
      return underlyingFs.getXAttrs(path, names);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public List<String> listXAttrs(final Path path) throws IOException {
    try {
      return underlyingFs.listXAttrs(path);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void removeXAttr(final Path path, final String name) throws IOException {
    try {
      underlyingFs.removeXAttr(path, name);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

    // TODO(DX-7629): Uncomment the following methods
//  @Override
//  public void setStoragePolicy(final Path src, final String policyName) throws IOException {
//    try {
//      underlyingFs.setStoragePolicy(src, policyName);
//    } catch(FSError e) {
//      throw propagateFSError(e);
//    }
//  }
//
//  @Override
//  public void unsetStoragePolicy(final Path src) throws IOException {
//    try {
//      underlyingFs.unsetStoragePolicy(src);
//    } catch(FSError e) {
//      throw propagateFSError(e);
//    }
//  }
//
//  @Override
//  public BlockStoragePolicySpi getStoragePolicy(final Path src) throws IOException {
//    try {
//      return underlyingFs.getStoragePolicy(src);
//    } catch(FSError e) {
//      throw propagateFSError(e);
//    }
//  }
//
//  @Override
//  public Collection<? extends BlockStoragePolicySpi> getAllStoragePolicies() throws IOException {
//    try {
//      return underlyingFs.getAllStoragePolicies();
//    } catch(FSError e) {
//      throw propagateFSError(e);
//    }
//  }

  public boolean isPdfs() {
    return isPdfs;
  }

  public boolean isMapRfs() {
    return isMapRfs;
  }

  public ImmutableList<FileStatus> listRecursive(Path path, boolean includeHiddenFiles) throws IOException {
    try {
      final ImmutableList.Builder<FileStatus> files = ImmutableList.builder();
      final FileStatus[] inputStatuses;
      if(includeHiddenFiles) {
        inputStatuses = underlyingFs.globStatus(path);
      } else {
        inputStatuses = underlyingFs.globStatus(path, DefaultPathFilter.INSTANCE);
      }
      populateRecursiveStatus(inputStatuses, files, true, includeHiddenFiles);
      return files.build();
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  public ImmutableList<FileStatus> list(Path path, boolean includeHiddenFiles) throws IOException {
    try {
      final ImmutableList.Builder<FileStatus> files = ImmutableList.builder();
      final FileStatus[] statuses = includeHiddenFiles ? underlyingFs.listStatus(path) :  underlyingFs.listStatus(path, DefaultPathFilter.INSTANCE);
      files.add(statuses);
      return files.build();
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  private void populateRecursiveStatus(FileStatus[] inputPaths, ImmutableList.Builder<FileStatus> outputPaths, boolean recursive, boolean includeHiddenFiles) throws FileNotFoundException, IOException {
    if(inputPaths == null || inputPaths.length == 0) {
      return;
    }

    for(FileStatus input : inputPaths) {
      outputPaths.add(input);
      if(recursive && input.isDirectory()) {
        final FileStatus[] statuses;
        if(includeHiddenFiles) {
          statuses = underlyingFs.listStatus(input.getPath());
        } else {
          statuses = underlyingFs.listStatus(input.getPath(), DefaultPathFilter.INSTANCE);
        }
        populateRecursiveStatus(statuses, outputPaths, recursive, includeHiddenFiles);
      }
    }
  }

  public InputStream openPossiblyCompressedStream(Path path) throws IOException {
    try {
      CompressionCodec codec = codecFactory.getCodec(path); // infers from file ext.
      if (codec != null) {
        return codec.createInputStream(open(path));
      } else {
        return open(path);
      }
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  /**
   * Canonicalizes a path if supported by the filesystem
   *
   * @param fs the filesystem to use
   * @param path the path to canonicalize
   * @return the canonicalized path, or the same path if not supported by the filesystem.
   *
   * @throws IOException
   */
  public static Path canonicalizePath(FileSystem fs, Path path) throws IOException {
    try {
      if (fs instanceof PathCanonicalizer) {
        return ((PathCanonicalizer) fs).canonicalizePath(path);
      }
      return path;
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public Path canonicalizePath(Path p) throws IOException {
    try {
      return canonicalizePath(underlyingFs, p);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  @Override
  public void fileOpened(Path path, FSDataInputStream fsDataInputStream) {
    openedFiles.put(fsDataInputStream, new DebugStackTrace(path, Thread.currentThread().getStackTrace()));
  }

  @Override
  public void fileClosed(FSDataInputStream fsDataInputStream) {
    openedFiles.remove(fsDataInputStream);
  }

  public static class DebugStackTrace {
    final private StackTraceElement[] elements;
    final private Path path;

    public DebugStackTrace(Path path, StackTraceElement[] elements) {
      this.path = path;
      this.elements = elements;
    }

    public void addToStringBuilder(StringBuffer sb) {
      sb.append("File '");
      sb.append(path.toString());
      sb.append("' opened at callstack:\n");

      // add all stack elements except the top three as they point to FileSystemWrapper.open() and inner stack elements.
      for (int i = 3; i < elements.length; i++) {
        sb.append("\t");
        sb.append(elements[i]);
        sb.append("\n");
      }
      sb.append("\n");
    }
  }

  FSDataInputStreamWrapper newFSDataInputStreamWrapper(Path f, final FSDataInputStream is) throws IOException {
    try {
      FSDataInputStreamWrapper result = (operatorStats != null) ? new FSDataInputStreamWithStatsWrapper(is, operatorStats) : new FSDataInputStreamWrapper(is);
      if (TRACKING_ENABLED) {
        result = new FSDataInputStreamWrapper(result) {
          @Override
          public void close() throws IOException {
            fileClosed(is);
            super.close();
          }
        };
        fileOpened(f, is);
      }
      return result;
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  FSDataOutputStreamWrapper newFSDataOutputStreamWrapper(FSDataOutputStream os) throws IOException {
    try {
      return new FSDataOutputStreamWrapper(os);
    } catch(FSError e) {
      throw propagateFSError(e);
    }
  }

  static IOException propagateFSError(FSError e) throws IOException {
    Throwables.propagateIfPossible(e.getCause(), IOException.class);
    return new IOException("Unexpected FSError", e);
  }
}
