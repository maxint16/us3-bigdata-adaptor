package cn.ucloud.ufile.fs;

import cn.ucloud.ufile.UfileClient;
import cn.ucloud.ufile.api.object.DeleteObjectApi;
import cn.ucloud.ufile.api.object.ObjectConfig;
import cn.ucloud.ufile.api.object.ObjectListWithDirFormatApi;
import cn.ucloud.ufile.api.object.multi.MultiUploadInfo;
import cn.ucloud.ufile.api.object.multi.MultiUploadPartState;
import cn.ucloud.ufile.auth.UfileObjectLocalAuthorization;
import cn.ucloud.ufile.bean.CommonPrefix;
import cn.ucloud.ufile.bean.MultiUploadResponse;
import cn.ucloud.ufile.bean.ObjectContentBean;
import cn.ucloud.ufile.bean.ObjectListWithDirFormatBean;
import cn.ucloud.ufile.bean.ObjectProfile;
import cn.ucloud.ufile.exception.UfileClientException;
import cn.ucloud.ufile.exception.UfileServerException;
import cn.ucloud.ufile.fs.common.VmdsAddressProvider;
import cn.ucloud.us3.ObjectListRequest;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileChecksum;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.InvalidRequestException;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathIOException;
import org.apache.hadoop.fs.PathIsNotEmptyDirectoryException;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Progressable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.ucloud.ufile.UfileConstants.MULTIPART_SIZE;
import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.IO_FILE_BUFFER_SIZE_KEY;


public class UFileFileSystem extends org.apache.hadoop.fs.FileSystem {

    /** UFile文件系统的Scheme名称 */
    public static String SCHEME = "ufile";

    public static Map<String, String> defaultUserMeta = null;

    /** 文件系统持有解析后的插件配置 */
    private Configure cfg = new Configure();

    /** 当前工作的URI ??? */
    private URI uri;

    /** 当前工作的路径 ??? */
    private Path workDir;

    /** ??? */
    private String username;

    /** 操作的UFile存储桶 */
    private String bucket;

    /** SDK文件操作需要的URL配置，避免每次调用生成一个 */
    public ObjectConfig objcfg;

    /** SDK文件操作需要的URL配置，避免每次调用生成一个 */
    private ObjectConfig objcfgForIO;

    /** SDK文件操作需要的公私钥信息，避免每次调用生成一个 */
    private UfileObjectLocalAuthorization objauth;

    private String rootPath;
    private UFileFileStatus rootStatus;
    private VmdsAddressProvider provider;

    public String getUsername() { return username; }

    public String toString() {
        return String.format("\n1. %s:%s\n 2. %s:%s\n 3. %s:%s\n 4. %s:%s\n 5. %s:%s",
                "uri", uri.toString(),
                "workDir", workDir.toString(),
                "username", username,
                "bucket", bucket,
                "rootDir", rootPath
        );
    }

    @Override
    public void initialize(URI name, Configuration conf) throws IOException {
        super.initialize(name, conf);
        conf.setInt(IO_FILE_BUFFER_SIZE_KEY, Constants.IO_FILE_BUFFER_SIZE_DEFAULT);
        setConf(conf);
        /** Username is the current user at the time the FS was instantiated. */
        username = UserGroupInformation.getCurrentUser().getShortUserName();
        try {
            uri = name;
            workDir = new Path(uri.toString());
            bucket = name.getAuthority();
            rootPath = String.format("%s://%s/",SCHEME, bucket);
            rootStatus = new UFileFileStatus(-1, true, 1, 1, -1, new Path(rootPath));

            cfg.Parse(conf);
            UFileUtils.Debug(cfg.getLogLevel(),"[initialize] cfg content:%s", cfg);
            objauth = new UfileObjectLocalAuthorization(cfg.getPublicKey(), cfg.getPrivateKey());
            if (cfg.isUseMDS()) {
                provider = new VmdsAddressProvider(cfg,vmdsAddress->{
                    ObjectConfig newObjcfg = new ObjectConfig("http://"+ vmdsAddress);
                    this.objcfg = newObjcfg;
                    this.cfg.setMDSHost("http://"+vmdsAddress);
                });
                try {
                    provider.startWatching();
                } catch (Exception e) {
                    throw new IOException("cannot watch zookeeper node");
                }
                if (cfg.getMDSHost().equals(bucket+"."+cfg.getEndPoint())){ throw new IOException("The metadata address format cannot be <bucket name>."+ cfg.getEndPoint()); }
            } else {
                objcfg = new ObjectConfig("http://" + bucket+"."+cfg.getEndPoint());
            }
            objcfgForIO = new ObjectConfig("http://" + bucket+"."+cfg.getEndPoint());
            /** 给SDK库安装拦截器 */
            UFileInterceptor.Install(cfg.getLogLevel(), cfg.getIoTimeout(), cfg.getRetryTimes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getScheme() { return UFileFileSystem.SCHEME; }

    @Override
    public URI getUri() { return uri; }

    /**
     * Opens an FSDataInputStream at the indicated Path.
     * @param f the file name to open
     * @param bufferSize the size of the buffer to be used.
     */
    @Override
    public FSDataInputStream open(Path f, int bufferSize) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(), "[open] path:%s, bufferSize:%d", f, bufferSize);

        // metadata service have no support
        UFileFileStatus ufs = innerGetFileStatus(f);
        if (ufs == null) {
            throw new FileNotFoundException("Can't open " + f
                    + " because it is nonexistent");
        }

        if (ufs.isDirectory()) {
            throw new FileNotFoundException("Can't open " + f
                    + " because it is a directory");
        }

        OSMeta osMeta;
        try {
            osMeta = UFileUtils.ParserPath(uri, workDir, f);
        } catch (Exception e) {
            throw new IOException(String.format("open parser path:%s ", f.toString()), e);
        }

        if (ufs.isArchive()) {
            UFileUtils.Debug(cfg.getLogLevel(), "[open] path:%s, but is archive type", f);
            int blockWaitTimes = 0;
            while (true) {
                if (ufs == null) {
                    throw new IOException(String.format("[open] file:%s's status is null under unfreezing!!", osMeta.getKey()));
                }
               UFileFileStatus.RestoreStatus rs = ufs.restoreStatus();
               ObjectRestoreExpiration ore = ufs.getORE();
               if (ore != null) {
                   UFileUtils.Debug(cfg.getLogLevel(), "[open] path:%s, onGoing:%b expiration:%d", osMeta.getKey(), ore.onGoing, ore.expiration);
               }

               /** 已经激活直接读取该文件 */
               if (0 == rs.compareTo(UFileFileStatus.RestoreStatus.RESTOED)) {
                   UFileUtils.Debug(cfg.getLogLevel(), "[open] path:%s, had resotred", f.toString());
                   break;
               }

                /** 正在解冻中，先睡眠，后重新获取文件状态信息 */
               if (0 == rs.compareTo(UFileFileStatus.RestoreStatus.UNFREEZING)) {
                   UFileUtils.Debug(cfg.getLogLevel(), "[open] path:%s, on unfreezing...", f.toString());
                   ufs.blockTimeForUnFreezing(blockWaitTimes++);
                   ufs = innerGetFileStatus(f);
                   continue;
               }

               if (0 == rs.compareTo(UFileFileStatus.RestoreStatus.UNRESTORE)) {
                   UFileUtils.Debug(cfg.getLogLevel(), "[open] path:%s, need restored", f.toString());
                   innerRestore(osMeta);
                   ufs = innerGetFileStatus(f);
                   continue;
               }

               if (0 == rs.compareTo(UFileFileStatus.RestoreStatus.UNKNOWN)) {
                   UFileUtils.Info(cfg.getLogLevel(), "[open] path:%s, is unknow status, jump from check loop", f.toString());
                   break;
               }
            }
            UFileUtils.Debug(cfg.getLogLevel(), "[open] path:%s, archive type can be readrestored, untile %d", f, ufs.getORE().expiration);
        }

        return new FSDataInputStream(
                new UFileInputStream(cfg,
                        objauth,
                        objcfgForIO,
                        bucket,
                        osMeta.getKey(),
                        ufs.getLen()));
    }

    @Override
    public FSDataOutputStream create(Path f, FsPermission permission, boolean overwrite, int bufferSize, short replication, long blockSize, Progressable progress) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(), "[create] path:%s, permission:%s overwrite:%b bufferSize:%d, replication:%d blockSize:%d", f,
                    permission, overwrite, bufferSize, replication, blockSize);
        OSMeta osMeta = UFileUtils.ParserPath(uri, workDir, f);
        try {
            FileStatus fs = getFileStatus(f);
            if (fs.isDirectory()) {
                throw new FileAlreadyExistsException(f.toString() + " is a directory");
            }
            if (!overwrite) {
                throw new FileAlreadyExistsException(f.toString() + "already exists");
            }
        } catch (FileNotFoundException ignore) {
            // excepted ignore
        }


        if (!cfg.isUseMDS()) { checkNeedMkParentDirs(f, permission); }

        if (cfg.isUseAsyncWIO()) {
            return new FSDataOutputStream(innerCreate(permission, osMeta, Constants.UPLOAD_DEFAULT_MIME_TYPE, blockSize), statistics);
        }
        return new FSDataOutputStream(innerCreate(permission, overwrite, bufferSize, replication, blockSize, progress, osMeta, true, objcfgForIO), statistics);
    }

    /**
     * 功能一: 普通文件写IO
     * 功能二: 创建目录文件
     * @return
     * @throws IOException
     */
    public UFileOutputStream innerCreate(FsPermission permission, boolean overwrite, int bufferSize, short replication, long blockSize, Progressable progress, OSMeta osMeta, Boolean needBuf, ObjectConfig  objCfg) throws IOException {
        return new UFileOutputStream(
                this,
                cfg,
                objauth,
                objCfg,
                osMeta,
                permission,
                overwrite,
                bufferSize,
                replication,
                blockSize,
                progress,
                needBuf);
    }

    /**
     * 功能一: 普通文件写IO
     * @return
     * @throws IOException
     */
    private UFileAsyncOutputStream innerCreate(FsPermission permission, OSMeta osMeta, String mimeType, long blockSize) throws IOException {
        return new UFileAsyncOutputStream(
              this,
              cfg,
              objauth,
              objcfgForIO,
              permission,
              osMeta,
              mimeType,
              blockSize);
    };

    @Override
    public FSDataOutputStream append(Path f, int bufferSize, Progressable progress) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(), "[append] path:%s, bufferSize:%d, progress:%s", f, bufferSize, progress);
        throw new UnsupportedOperationException("Append is not supported " + "by UFileFileSystem");
    }

    @Override
    public boolean rename(Path src, Path dst) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(), "[rename] src path:%s, dst path:%s", src, dst);
        try {
            return innerRename(src, dst);
        } catch (UfileClientException|UfileServerException e) {
            throw UFileUtils.TranslateException("rename", src.toString() + " => " + dst.toString(), e);
        }
    }

    private boolean innerRename(Path src, Path dst) throws IOException, UfileClientException, UfileServerException {
        OSMeta srcOsMeta = UFileUtils.ParserPath(uri, workDir, src);
        OSMeta dstOsMeta = UFileUtils.ParserPath(uri, workDir, dst);

        if (srcOsMeta.getKey().isEmpty()) {
            throw new IOException(String.format("%s -> %s, source is root directory", srcOsMeta.getKey(),
                    dstOsMeta.getKey()));
        }

        if (dstOsMeta.getKey().isEmpty()) {
            throw new IOException(String.format("%s -> %s, dest is root directory", srcOsMeta.getKey(),
                    dstOsMeta.getKey()));
        }

        if (srcOsMeta.getKey().equals(dstOsMeta.getKey())) {
            throw new IOException(String.format("%s -> %s, source same with dest ", srcOsMeta.getKey(),
                    dstOsMeta.getKey()));
        }

        UFileFileStatus srcFs = innerGetFileStatus(src);
        if (srcFs == null) {
            throw new FileNotFoundException(String.format("%s -> %s, source is not exists", srcOsMeta.getKey(),
                    dstOsMeta.getKey()));
        }
        /**
         * 暂不支持MDS
        if (cfg.isUseMDS()) {
            innerCopyFile(srcOsMeta, dstOsMeta, null, null);
            delete(src, false);
            return true;
        }
         */

        Path srcParent =  src.getParent();
        OSMeta srcParentMeta = UFileUtils.ParserPath(uri, workDir, srcParent);
        Path dstParent =  dst.getParent();
        if (dstOsMeta.getKey().startsWith(srcOsMeta.getKey()) &&
            dstOsMeta.getKey().toCharArray()[srcOsMeta.getKey().length()] == '/') {
            throw new IOException(String.format("%s -> %s, cannot rename a directory to a subdirectory of itsel", srcOsMeta.getKey(),
                    dstOsMeta.getKey()));
        }

        FileStatus dstFs = null;
        try {
            dstFs = getFileStatus(dst);
        } catch (FileNotFoundException e) {
            // ignore
        }
        if (dstFs == null ) {
            /** 父目录必须存在 */
            UFileUtils.Debug(cfg.getLogLevel(),"[innerRename] dst:%s not exist", dst);
            OSMeta dstParentOsMeta = UFileUtils.ParserPath(uri, workDir, dstParent);
            if (!dstParentOsMeta.getKey().isEmpty()) {
                /** 如果目的端的父目录不存在 or 父目录不是目录都认为异常 */
                UFileUtils.Debug(cfg.getLogLevel(),"[innerRename] dst:%s check parent:%s exist", dst, dstParentOsMeta.getKey());
                FileStatus dstPFs = innerGetFileStatus(dst.getParent());
                if (dstPFs == null) {
                    throw new FileNotFoundException(String.format("%s -> %s, dest has no parent", srcOsMeta.getKey(),
                            dstOsMeta.getKey()));
                }

                if (!dstPFs.isDirectory()) {
                    throw new IOException(String.format("%s -> %s, dest is not a directory", srcOsMeta.getKey(),
                            dstOsMeta.getKey()));
                }
                UFileUtils.Debug(cfg.getLogLevel(),"[innerRename] dst:%s parent:%s exist", dst, dstParentOsMeta.getKey());
            }
        } else {
            UFileUtils.Debug(cfg.getLogLevel(),"[innerRename] dst:%s exist", dst);
            if (srcFs.isDirectory()) {
                // 源地址为目录
                if (dstFs.isFile()) {
                    /** 不能把目录重命名为已有的文件 */
                    throw new FileAlreadyExistsException(String.format("%s -> %s, source is directory, but dst is file", srcOsMeta.getKey(),
                            dstOsMeta.getKey()));
                } else {
                    // 不支持rename到不为空的目录
                    String prefix = dstOsMeta.getKey();
                    if (!prefix.isEmpty() && !prefix.endsWith("/")) {
                        prefix = prefix + "/";
                    }
                    ObjectListRequest request = ObjectListRequest.builder()
                            .bucketName(bucket)
                            .prefix(prefix)
                            .delimiter(Constants.LIST_OBJECTS_DEFAULT_DELIMITER)
                            .limit(10)
                            .build();
                    ObjectListWithDirFormatBean objectList = listObjects(request);
                    if (objectList.getObjectContents().size() + objectList.getCommonPrefixes().size() > 0) {
                        return false;
                    }
                }
            } else {
                // 源地址为目录
                if (dstFs.isFile()) {
                    return false;
                }
            }
        }

        if (srcFs.isFile()) {
            /** 从文件拷贝到目录, 经过前面的拦截判断, 目的端只可能是根目录、空目录或者是不存在的目录 */
            if (dstFs != null && dstFs.isDirectory()) {
                /** 文件到目录下 */
                String newDstKey = dstOsMeta.getKey();
                if (!newDstKey.isEmpty() && !newDstKey.endsWith("/")) {
                    newDstKey += "/";
                }
                newDstKey += srcOsMeta.getKey().substring(srcParentMeta.getKey().length());
                UFileUtils.Debug(cfg.getLogLevel(), "[innerRename] src:%s is file, dst:%s is dir, newDst:%s ",
                        srcOsMeta.getKey(), dstOsMeta.getKey(), newDstKey);
                dstOsMeta.setKey(newDstKey);
                copyFile(srcOsMeta.getKey(), newDstKey);
            } else if (dstFs == null) {
                /** 文件到文件 */
                copyFile(srcOsMeta.getKey(), dstOsMeta.getKey());
            } else {
                return false;
            }
            innerDelete(src, false, srcFs);
        } else {
            /** 从目录到目录的拷贝 */
            String dstKey = dstOsMeta.getKey();
            String srcKey = srcOsMeta.getKey();

            if (!dstKey.isEmpty() && !dstKey.endsWith("/")) dstKey += "/";
            if (!srcKey.isEmpty() && !srcKey.endsWith("/")) srcKey += "/";

            if (dstKey.startsWith(srcKey)) {
                throw new PathIOException(String.format("[innerRename] dir dst:%s has under dir src:%s ",
                        srcKey,
                        dstKey));
            }

            UFileUtils.Debug( cfg.getLogLevel(), "[innerRename] srcKey dir:%s to dstKey dir:%s ",
                    srcKey,
                    dstKey);
            if (dstFs != null) {
                // 需要将旧的空目录删除，前面的逻辑判断已经检查过dstFs为目录且为空，因此这里直接调用删除是安全的
                delete(dst, false);
            }
            ObjectListRequest listRequest = ObjectListRequest.builder()
                    .bucketName(bucket)
                    .prefix(srcKey)
                    .limit(Constants.LIST_OBJECTS_DEFAULT_LIMIT)
                    .build();
            ObjectListWithDirFormatBean objectList = listObjects(listRequest);
            while (true) {
                // 不带delimiter参数时，所有返回内容均为对象形式
                for (ObjectContentBean objectContent : objectList.getObjectContents()) {
                    OSMeta copySrcMeta = new OSMeta(objectContent.getBucketName(), objectContent.getKey());
                    OSMeta copyDstMeta = new OSMeta(bucket, copySrcMeta.getKey().replace(srcKey, dstKey));
                    copyFile(copySrcMeta.getKey(), copyDstMeta.getKey());
                    deleteObject(copySrcMeta.getKey());
                }

                if (!objectList.getTruncated()) break;
                objectList = continueListObjects(objectList);
            }
        }
        return true;
    }

    @Override
    public boolean delete(Path f, boolean recursive) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(), "[delete] path:%s, recursive:%b", f, recursive);
        try {
            UFileFileStatus ufs = innerGetFileStatus(f);
            if (ufs == null) throw new FileNotFoundException(String.format("%s is not exist", f.toString()));
            return innerDelete(f, recursive, ufs);
        } catch (FileNotFoundException e) {
            return false;
        } catch (UfileClientException|UfileServerException e) {
            throw UFileUtils.TranslateException("[delete] delete failed", f.toString(), e);
        }
    }

    private boolean innerDelete(Path f, boolean recursive, UFileFileStatus ufs) throws IOException, UfileClientException, UfileServerException {
        OSMeta osMeta = UFileUtils.ParserPath(uri, workDir, f);
        if (!cfg.isUseMDS()) {
            if (ufs == null) {
                throw new FileNotFoundException(String.format("[innerDelete] %s not found", osMeta.getKey()));
            }

            if (!ufs.isDirectory()) {
                UFileUtils.Debug(cfg.getLogLevel(), "[innerDelete] f:%s is file recursive:%b", f, recursive);
                deleteObject(osMeta.getKey());
            } else {
                UFileUtils.Debug(cfg.getLogLevel(), "[innerDelete] f:%s is dir recursive:%b", f, recursive);
                String key = osMeta.getKey();
                if (!key.isEmpty() && !key.endsWith("/")) {
                    key += "/";
                }

                ObjectListRequest listRequest = ObjectListRequest.builder()
                        .bucketName(bucket)
                        .prefix(key)
                        .limit(Constants.LIST_OBJECTS_DEFAULT_LIMIT)
                        .build();
                ObjectListWithDirFormatBean objectListBean = listObjects(listRequest);
                while(true) {
                    if (objectListBean.getObjectContents().size() > 1 && !recursive) {
                        throw new PathIsNotEmptyDirectoryException(key);
                    }
                    for (ObjectContentBean objectContent : objectListBean.getObjectContents()) {
                        // 当前US3没有提供批量删除接口
                        deleteObject(objectContent.getKey());
                    }
                    if (objectListBean.getTruncated()) {
                        objectListBean = continueListObjects(objectListBean);
                    } else {
                        break;
                    }
                }
                /**
                 * 由于创建目录时会在对象存储上创建2个对象
                 * 原因参考 innerMkdir 方法注释
                 * 一个是 mime-type 为 application/x-directory 的对象，通过listObject接口查询 key 为 '/' 结尾的目录名
                 * 另一个 mime-type 为 file/path 的对象，通过listObject 接口查询 key 为结尾不带 '/' 的目录名
                 * 因此在删除的时候需要将两个 key 都进行删除
                 * @Deprecated 2023.07.06
                 * FIXME: 创建目录创建2个对象已修复 下个版本删除这部分代码
                 */
                /*try {
                    deleteObject(osMeta.getKey());
                } catch (InvalidRequestException e) {
                    // 不对根目录做删除操作
                    if (!Constants.CANNOT_DELETE_ROOT.equals(e.getMessage())) {
                        throw e;
                    }
                }*/
            }
        }
        return true;
    }

    private void deleteObject(String key) throws UfileClientException, UfileServerException, IOException {
        /**
         * key需要使用 OsMeta对象的 getKey 否则容易输入错误的路径，导致删除接口报404异常、删错文件等问题
         */
        blockRootDelete(key);
        DeleteObjectApi request = UfileClient.object(objauth, objcfg).deleteObject(key, bucket);
        /*有可能发给mds，删除目录时会耗时长*/
        request.setReadTimeOut(300*1000);
        request.execute();
        /**
         * 跟AWS-S3对齐，不保证数据一致性，可能存在时间差
         */
//        UFileUtils.KeepListFileExistConsistency(this, key, Constants.DEFAULT_MAX_TRYTIMES*5, false);
    }

    @Override
    public FileStatus[] listStatus(Path f) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(), "[listStatus] path:%s ", f);
        // 保持跟HDFS语义一致
        UFileFileStatus ufs = innerGetFileStatus(f);
        if (ufs == null) throw new FileNotFoundException(String.format("%s not exist!!", f));
        if (ufs.isFile()) {
            UFileFileStatus[] stats = new UFileFileStatus[1];
            stats[0]= ufs;
            return stats;
        }
        return innerListStatus(f, ufs);
    }

    private UFileFileStatus[] innerListStatus(Path f, UFileFileStatus fileStatus) throws IOException{
        return innerListStatusWithSize(f, fileStatus, false, Constants.LIST_OBJECTS_DEFAULT_LIMIT, false);
    }

    public UFileFileStatus[] innerListStatusWithSize(Path f, UFileFileStatus fileStatus, boolean isOneLoop, int dataLimit, boolean forStatus) throws IOException {
        if (fileStatus.isDirectory()) {
          return genericInnerListStatusWithSize(f, true, isOneLoop, dataLimit, forStatus);
        } else {
            UFileFileStatus[] stats = new UFileFileStatus[1];
            stats[0]= fileStatus;
            return stats;
        }
    }

    public UFileFileStatus[] genericInnerListStatusWithSize(Path f, boolean isDir, boolean isOneLoop, int dataLimit, boolean forStatus) throws IOException {
        OSMeta osMeta = UFileUtils.ParserPath(uri, workDir, f);
        String prefix = osMeta.getKey();
        return genericInnerListStatusWithSize(prefix, isDir, isOneLoop, dataLimit, forStatus);
    }

    public UFileFileStatus[] genericInnerListStatusWithSize(String prefix, boolean isDir, boolean isOneLoop, int dataLimit, boolean forStatus) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(), "[innerListStatusWithSize] path:%s ", prefix);
        int idx = 0;
        if (isDir) {
            List<UFileFileStatus> result = new ArrayList<UFileFileStatus>();
            if (!prefix.isEmpty() && !forStatus) if (!prefix.endsWith("/")) prefix = prefix + '/';

            String nextMark = "";
            Exception exception = null;
            /** 用来临时存储目录信息，用来过滤文件中老插件不以"/"结尾的目录
             *  不过该方法有个条件保证，就是得目录先返回，不过确认后端服务是
             *  保证了该条件。
             */
            HashMap hm = new HashMap<>();
            int retryCount = 1;
            while (true) {
                try {
                    ObjectListWithDirFormatApi request = UfileClient.object(objauth, objcfg)
                            .objectListWithDirFormat(bucket)
                            .withPrefix(prefix)
                            .withMarker(nextMark)
                            .withDelimiter(Constants.LIST_OBJECTS_DEFAULT_DELIMITER)
                            .dataLimit(dataLimit);

                    ObjectListWithDirFormatBean response = request.execute();
                    List<CommonPrefix> dirs = response.getCommonPrefixes();
                    if (dirs != null) {
                        for (int i = 0; i < dirs.size(); i++) {
                            CommonPrefix cp = dirs.get(i);
                            hm.put(cp.getPrefix(), idx);
                            idx++;
                            UFileUtils.Debug(cfg.getLogLevel(), "[innerListStatus][%d] common prefix:%s ", i, dirs.get(i).getPrefix());
                            result.add(new UFileFileStatus(0,
                                    true,
                                    1,
                                    0,
                                    0,
                                    0,
                                    null,
                                    username,
                                    Constants.superGroup,
                                    new Path(rootPath, cp.getPrefix())));
                        }
                    }

                    List<ObjectContentBean> objs = response.getObjectContents();
                    if (objs != null) {
                        for (int i = 0; i < objs.size(); i++) {
                            UFileUtils.Debug(cfg.getLogLevel(), "[innerListStatus][%d] key:%s lastModified:%d ", i, objs.get(i).getKey(), objs.get(i).getLastModified());
                            ObjectContentBean obj = objs.get(i);
                            Map<String, String> userMeta = obj.getUserMeta();
                            String key = obj.getKey();

                            if (obj.getMimeType().equals(Constants.DIRECTORY_MIME_TYPE_1)) {
                                key += "/";
                                /**
                                 *   证明这就是一个目录
                                 *   新插件会创建一个以"/"的目录和一个mimetype是"file/path"的目录
                                 *   是为了兼容GET时的问题
                                 */
                            } else if (!obj.getMimeType().equals(Constants.DIRECTORY_MIME_TYPE_2)) {
                                result.add(new UFileFileStatus(this,
                                        Long.parseLong(obj.getSize()),
                                        false,
                                        obj.getLastModified() * 1000,
                                        obj.getLastModified() * 1000,
                                        new Path(rootPath, key),
                                        userMeta));
                                continue;
                            }

                            /** 证明这是一个目录 */
                            if (hm.containsKey(key)) {
                                int index = (int) (hm.get(key));
                                result.set(index, new UFileFileStatus(this,
                                        0,
                                        true,
                                        obj.getLastModified() * 1000,
                                        obj.getLastModified() * 1000,
                                        new Path(rootPath, key),
                                        userMeta));
                            } else {
                                result.add(new UFileFileStatus(this,
                                        0,
                                        true,
                                        obj.getLastModified() * 1000,
                                        obj.getLastModified() * 1000,
                                        new Path(rootPath, key),
                                        userMeta));
                            }
                        }
                    }

                    nextMark = response.getNextMarker();
                    if (nextMark.equals("") || isOneLoop ) {
                        UFileUtils.Debug(cfg.getLogLevel(), "[innerListStatus] encounters the end");
                        return result.toArray(new UFileFileStatus[result.size()]);
                    }
                    continue;
                } catch (UfileClientException e) {
                    retryCount++;
                    if(retryCount >= Constants.DEFAULT_MAX_TRYTIMES){
                    exception = e;
                    UFileUtils.Error(cfg.getLogLevel(),"[innerListStatus] client, %s ", e.toString());
                    throw UFileUtils.TranslateException("[innerListStatus]", prefix, exception);
                    }
                } catch (UfileServerException e) {
                    retryCount++;
                    if(retryCount>=Constants.DEFAULT_MAX_TRYTIMES||e.getErrorBean().getResponseCode()<500){
                    exception = e;
                    if (e.getErrorBean().getResponseCode()/100 == 4) {
                        throw UFileUtils.TranslateException("[innerListStatus]", prefix, exception);
                    }
                    UFileUtils.Error(cfg.getLogLevel(),"[innerListStatus] server, %s ", e.toString());
                    throw UFileUtils.TranslateException("[innerListStatus]", prefix, exception);
                }
                }
            } // end of while statement
        }
        return UFileFileStatus.ufs;
    }

    @Override
    public synchronized void setWorkingDirectory(Path new_dir) {
        UFileUtils.Debug(cfg.getLogLevel(), "[setWorkingDirectory] path:%s ", new_dir);
        workDir = new_dir;
    }

    @Override
    public Path getWorkingDirectory() {
        UFileUtils.Debug(cfg.getLogLevel(), "[getWorkingDirectory] working directory:%s", workDir);
        return workDir;
    }

    @Override
    public boolean mkdirs(Path f, FsPermission permission) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(), "[mkdirs] path:%s, FsPermission:%s", f, permission);
        if (!cfg.isUseMDS()) { checkNeedMkParentDirs(f, permission); }
        return innerMkdir(f, permission);
    }

    private boolean innerMkdir(Path f, FsPermission permission) throws IOException {
        /**
         * FIXME: 建议参考 S3AFileSystem实现，不做 FsPermission 的实现
         * 按照当前方式的 FsPermission 实现并不能完整实现对应行为
         * 如果想要完整实现相关行为，在创建文件等操作时都要考虑是否继承上层目录的 permission 代价很高
         */
        UFileUtils.Debug(cfg.getLogLevel(), "[innerMkdir] path:%s, FsPermission:%s", f, permission);
        UFileFileStatus ufs;
        ufs = innerGetFileStatus(f);
        if (ufs != null) {
            if (ufs.isDirectory()) return true;
            else throw new FileAlreadyExistsException("Path is a file:" + f);
        }
        // 此处fPart不可能为空，如果 f 是 root 应该在上面直接被return
        Path fPart = f.getParent();
        do {
            // root 层为虚拟目录 一定会提前退出
            ufs = innerGetFileStatus(fPart);
            if (ufs == null) {
                fPart = fPart.getParent();
                continue;
            }
            if (ufs.isDirectory()) {
                break;
            }
            if (ufs.isFile()) {
                throw new FileAlreadyExistsException(String.format(
                        "Can't make directory for path '%s' since it is a file.",
                        fPart));
            }
            fPart = fPart.getParent();
        } while (fPart != null);
        OSMeta osMeta = UFileUtils.ParserPath(uri, workDir, f);
        if (!osMeta.getKey().endsWith("/")) {
            osMeta.setKey(osMeta.getKey() + "/");
        }
        UFileOutputStream stream =innerCreate(permission, false, 0, (short) 0,0 , null, osMeta, false, objcfg);
        stream.close();
        return true;
    }

    private void checkNeedMkParentDirs(Path f, FsPermission permission) throws IOException {
        /**
         * 创建目录时递归创建父目录
         * FIXME:
         * 这里存在一种特殊情况，通过其他客户端创建了一个 filename 中带有 '/'的对象
         * 通过本客户端查询时会以目录的形式呈现，但其父目录并不存在，当这个文件被删除后
         * 后续listStatus或getStatus都查询不到相关目录信息
         * 建议按照S3AFileSystem的逻辑行为进行修改，mkdir不做父目录创建，在delete或rename的时候
         * 判断是否需要创建fakeDir
         */
        UFileUtils.Debug(cfg.getLogLevel(), "[checkNeedMkParentDir] path:%s", f);
        UFileFileStatus ufs;
        f = f.getParent();
        UFileUtils.Debug(cfg.getLogLevel(), "[checkNeedMkParentDir] parent path:%s", f);
        ufs = innerGetFileStatus(f);
        /** 不存在则创建 */
        if (ufs == null) {
            checkNeedMkParentDirs(f, permission);
            innerMkdir(f, permission);
        }
        /** 只要到上级目录碰到存在就结束，因为root目录是虚拟出来的，所以会最多到根目录结束 */
        else if (ufs.isDirectory()) return;
        else if (ufs.isFile()) throw new FileAlreadyExistsException(String.format("need mkdir ,but %s is file", f));
    }

    /**
     * 返回一个代表Path的FileStatus对象
     * @param f
     * @return 一个FileStatus对象
     * @throws IOException
     */
    @Override
    public UFileFileStatus getFileStatus(Path f) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(), "[getFileStatus] path:%s", f);
        UFileFileStatus ufs = innerGetFileStatus(f);
        if (ufs == null) throw new FileNotFoundException(String.format("%s not exist!!", f));
        return ufs;
    }

    private UFileFileStatus innerGetFileStatus(Path f) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(), "[innerGetFileStatus] path:%s", f);
        final Path path = qualify(f);
        String key = pathToKey(path);
        UFileUtils.Debug(cfg.getLogLevel(), "[innerGetFileStatus] Getting path status for %s  (%s)", path.toString(), key);
        if (!key.isEmpty()) {
            try {
                ObjectProfile objectProfile = getObjectProfile(key);
                UFileFileStatus ufs;
                if (objectRepresentsDirectory(key, objectProfile.getContentLength())
                        // 老版本中 以下两种类型的对象被识别为 目录，为兼容老版本添加这段逻辑
                        || Constants.DIRECTORY_MIME_TYPE_2.equals(objectProfile.getContentType())
                        || Constants.DIRECTORY_MIME_TYPE_1.equals(objectProfile.getContentType())) {
                    UFileUtils.Debug(cfg.getLogLevel(), "[innerGetFileStatus] Found exact file: fake directory");
                    ufs = new UFileFileStatus(
                            this,
                            0,
                            true,
                            parseStringDateToLong(objectProfile.getLastModified()),
                            parseStringDateToLong(objectProfile.getLastModified()),
                            path,
                            null);
                } else {
                    UFileUtils.Debug(cfg.getLogLevel(), "[innerGetFileStatus] Found exact file: normal file");
                    ufs = new UFileFileStatus(
                            this,
                            objectProfile.getContentLength(),
                            false,
                            parseStringDateToLong(objectProfile.getLastModified()),
                            parseStringDateToLong(objectProfile.getLastModified()),
                            path,
                            null);
                }
                try {
                    if (UFileUtils.isArchive(objectProfile.getStorageType())) {
                        ObjectRestoreExpiration ore = UFileUtils.ParserRestore(objectProfile.getRestoreTime());
                        ufs.setORE(ore);
                    }
                    ufs.setStorageType(objectProfile.getStorageType());
                    return ufs;
                } catch (ParseException e) {
                    // unknown exception
                    UFileUtils.Error(cfg.getLogLevel(), "[innerGetFileStatus] Parse storage type error: %s", e.getMessage(), e);
                }
            } catch (UfileServerException e) {
                if (e.getErrorBean().getResponseCode() != 404) {
                    throw UFileUtils.TranslateException("innerGetFileStatus", path.toString(), e);
                }
            } catch (UfileClientException e) {
                throw UFileUtils.TranslateException("innerGetFileStatus", path.toString(), e);
            }

            if (!key.endsWith("/")) {
                // 可能是一个路径
                key = key + "/";
                try {
                    ObjectProfile objectProfile = getObjectProfile(key);
                    UFileFileStatus ufs;
                    if (objectRepresentsDirectory(key, objectProfile.getContentLength())
                            // 老版本中 以下两种类型的对象被识别为 目录，为兼容老版本添加这段逻辑
                            || Constants.DIRECTORY_MIME_TYPE_2.equals(objectProfile.getContentType())
                            || Constants.DIRECTORY_MIME_TYPE_1.equals(objectProfile.getContentType())) {
                        UFileUtils.Debug(cfg.getLogLevel(), "[innerGetFileStatus] Found exact file: fake directory");
                        ufs = new UFileFileStatus(
                                this,
                                0,
                                true,
                                parseStringDateToLong(objectProfile.getLastModified()),
                                parseStringDateToLong(objectProfile.getLastModified()),
                                path,
                                null);
                    } else {
                        // 以 / 结尾的真实文件，不应该存在
                        UFileUtils.Error(cfg.getLogLevel(), "[innerGetFileStatus] Found exact file: normal file with key:", key);
                        ufs = new UFileFileStatus(
                                this,
                                objectProfile.getContentLength(),
                                false,
                                parseStringDateToLong(objectProfile.getLastModified()),
                                parseStringDateToLong(objectProfile.getLastModified()),
                                path,
                                null);
                    }
                    try {
                        if (UFileUtils.isArchive(objectProfile.getStorageType())) {
                            ObjectRestoreExpiration ore = UFileUtils.ParserRestore(objectProfile.getRestoreTime());
                            ufs.setORE(ore);
                        }
                        ufs.setStorageType(objectProfile.getStorageType());
                        return ufs;
                    } catch (ParseException e) {
                        // unknown exception
                        UFileUtils.Error(cfg.getLogLevel(), "[innerGetFileStatus] Parse storage type error: %s", e.getMessage(), e);
                    }
                } catch (UfileServerException e) {
                    if (e.getErrorBean().getResponseCode() != 404) {
                        throw UFileUtils.TranslateException("innerGetFileStatus", path.toString(), e);
                    }
                } catch (UfileClientException e) {
                    throw UFileUtils.TranslateException("innerGetFileStatus", path.toString(), e);
                }
            }
        }

        try {
            key = maybeAddTrailingSlash(key);
            // key可能是一个路径
            ObjectListRequest request = ObjectListRequest.builder()
                    .bucketName(bucket)
                    .limit(1)
                    .prefix(key)
                    .delimiter(Constants.LIST_OBJECTS_DEFAULT_DELIMITER)
                    .build();
            ObjectListWithDirFormatBean objectList = listObjects(request);
            if (!objectList.getCommonPrefixes().isEmpty()
                    || !objectList.getObjectContents().isEmpty()) {
                return new UFileFileStatus(
                        this,
                        0,
                        true,
                        0L,
                        0L,
                        path,
                        null);
            } else if (key.isEmpty()) {
                UFileUtils.Debug(cfg.getLogLevel(), "[getFileStatus] Found root directory");
                return rootStatus;
            }
        } catch (UfileServerException e) {
            if (e.getErrorBean().getResponseCode() != 404) {
                throw UFileUtils.TranslateException("getFileStatus", path.toString(), e);
            }
        } catch (UfileClientException e) {
            throw UFileUtils.TranslateException("getFileStatus", path.toString(), e);
        }

        UFileUtils.Debug(cfg.getLogLevel(), "Not Found: %s", path.toString());
        return null;
    }

    /**
     * 关闭文件系统
     * @throws IOException
     */
    @Override
    public void close() throws IOException {
        try {
            if(provider != null){
                provider.stopWatching();
            }
            super.close();
        } finally {
            // TODO
        }
    }

    @Override
    public FileChecksum getFileChecksum(Path f) throws IOException {
        UFileFileStatus fs = innerGetFileStatus(f);
        if (fs == null) {
            UFileUtils.Info(cfg.getLogLevel(),"[getFileChecksum] f:%s is dir", f);
            throw new FileNotFoundException("[getFileChecksum] f:" + f.toString() + " no exist");
        } else if (fs.isDirectory()) {
            UFileUtils.Info(cfg.getLogLevel(),"[getFileChecksum] f:%s is dir", f);
            return null;
        }

        String hexCrc32c = fs.getHexCrc32c();
        if (hexCrc32c == null) {
            UFileUtils.Info(cfg.getLogLevel(),"[getFileChecksum] f:%s' crc32c is empty ", f.toString());
            return null;
        }

        UFileUtils.Debug(cfg.getLogLevel(),"[getFileChecksum] f:%s' crc32c is %s", f, hexCrc32c);
        return new UFileFileChecksum(hexCrc32c);
    }

    @Override
    public void setOwner(Path f, String username, String groupname
    ) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(),"[setOwner] f:%s' username:%s groupname:%s  ", f, username, groupname);
        OSMeta osMeta = UFileUtils.ParserPath(uri, workDir, f);
        UFileFileStatus ufs = innerGetFileStatus(f);
        if (ufs == null) {
            UFileUtils.Info(cfg.getLogLevel(),"[setOwner] ufile status is empty after head again ");
            return;
        }

        if (username == null || username.equals("")) username = ufs.getOwner();
        ufs.setOverrideUserName(username);

        if (groupname == null || groupname.equals("")) groupname = ufs.getGroup();
        ufs.setOverrideGroupName(groupname);

        Map<String, String> userMeta = extractUserMeta(username,groupname,ufs.getHexCrc32c(),ufs.getPermission(),
                ufs.getBlockSize(), ufs.getReplication(), ufs.getBase64Md5());

        replaceUserMeta(osMeta, ufs, userMeta);
    }

    @Override
    public void setPermission(Path p, FsPermission permission) throws IOException {
        OSMeta osMeta = UFileUtils.ParserPath(uri, workDir, p);
        UFileFileStatus ufs = innerGetFileStatus(p);
        if (ufs == null) {
            throw new FileNotFoundException();
        }

        String userName = ufs.getOverrideGroupName();
        String groupName = ufs.getOverrideGroupName();
        if (userName == null || userName.equals("")) userName = ufs.getOwner();
        if (groupName == null || groupName.equals("")) groupName = ufs.getGroup();
        Map<String, String> userMeta = extractUserMeta(userName,
                    groupName,
                    ufs.getHexCrc32c(),
                    permission,
                    ufs.getBlockSize(),
                    ufs.getReplication(),
                    ufs.getBase64Md5());
        replaceUserMeta(osMeta, ufs, userMeta);
    }

    private void innerCopyFile(OSMeta srcOsMeta, OSMeta dstOsMeta, String directive, Map<String, String> userMeta) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(),"[innerCopyFile] src:%s/%s dst:%s/%s directive:%s  ",
                srcOsMeta.getBucket(),
                srcOsMeta.getKey(),
                dstOsMeta.getBucket(),
                dstOsMeta.getKey(),
                directive);
        //Iterator<String> it = userMeta.keySet().iterator();
        //while(it.hasNext() && cfg.getLogLevel().ordinal() <= LOGLEVEL.DEBUG.ordinal()) {
        //    String key = it.next();
        //    UFileUtils.Debug(cfg.getLogLevel(),"[innerCopyFile] userMeta key:%s val:%s  ",  key, userMeta.get(key));
        //}
        int retryCount = 1;
        while(true){
        try {
            UfileClient.object(objauth, objcfg)
            .copyObject(srcOsMeta.getBucket(), srcOsMeta.getKey())
            .copyTo(dstOsMeta.getBucket(), dstOsMeta.getKey())
            .withMetadataDirective(directive)
            .withMetaDatas(userMeta)
            .execute();
            return;
        } catch (UfileClientException e) {
            UFileUtils.Error(cfg.getLogLevel(),"[innerCopyFile] client, %s ", e.toString());
            if(retryCount >= Constants.DEFAULT_MAX_TRYTIMES)
            throw UFileUtils.TranslateException(String.format("[innerCopyFile] %s to %s", srcOsMeta.getKey(), dstOsMeta.getKey()), srcOsMeta.getKey(), e);
        } catch (UfileServerException e) {
            if (e.getErrorBean().getResponseCode() == Constants.API_NOT_FOUND_HTTP_STATUS) {
                UFileUtils.Info(cfg.getLogLevel(),"[innerCopyFile] server, %s or %s is not found", srcOsMeta.getKey(), dstOsMeta.getKey());
                return;
            }

            UFileUtils.Error(cfg.getLogLevel(),"[innerCopyFile] server, %s ", e.toString());
            if(retryCount>=Constants.DEFAULT_MAX_TRYTIMES||e.getErrorBean().getResponseCode()<500)
            throw UFileUtils.TranslateException(String.format("[innerCopyFile] %s to %s", srcOsMeta.getKey(), dstOsMeta.getKey()), srcOsMeta.getKey(), e);
        }finally{
            retryCount ++;
            try {
                Thread.sleep(retryCount* Constants.TRY_DELAY_BASE_TIME);
            } catch (InterruptedException e) {
                throw new IOException("not able to handle exception", e);
            }
        }
    }
    }

    /**
     * 对于冷存文件进行激活操作
     * @param osMeta
     * @throws IOException
     */
    private void innerRestore(OSMeta osMeta) throws IOException {
        int retryCount = 1;
        try {
            UfileClient.object(objauth, objcfg)
                    .objectRestore(osMeta.getKey(), osMeta.getBucket())
                    .execute();
            return;
        } catch (UfileClientException e) {
            UFileUtils.Error(cfg.getLogLevel(),"[innerRestore] client, %s ", e.toString());
            if(retryCount >= Constants.DEFAULT_MAX_TRYTIMES)
            throw UFileUtils.TranslateException(String.format("[innerRestore] %s %s", osMeta.getBucket(), osMeta.getKey()), osMeta.getKey(), e);
        } catch (UfileServerException e) {
            UFileUtils.Error(cfg.getLogLevel(),"[innerRestore] server, %s ", e.toString());
            if(retryCount>=Constants.DEFAULT_MAX_TRYTIMES||e.getErrorBean().getResponseCode()<500)
            throw UFileUtils.TranslateException(String.format("[innerRestore] %s %s", osMeta.getBucket(), osMeta.getKey()),  osMeta.getKey(), e);
        }finally{
            retryCount ++;
            try {
                Thread.sleep(retryCount* Constants.TRY_DELAY_BASE_TIME);
            } catch (InterruptedException e) {
                throw new IOException("not able to handle exception", e);
            }
        }
    }

    public Map<String, String> extractUserMeta(String defOwner, String defGroup, String crc32c, FsPermission permission,
                                               long blockSize, short replication, String base64Md5) {
        Map<String, String> userMeta = new HashMap<>();
        userMeta.put(Constants.HDFS_PERMISSION_USER_KEY, UFileUtils.EncodeFsAction(permission.getUserAction()));
        userMeta.put(Constants.HDFS_PERMISSION_GROUP_KEY, UFileUtils.EncodeFsAction(permission.getGroupAction()));
        userMeta.put(Constants.HDFS_PERMISSION_OTHER_KEY, UFileUtils.EncodeFsAction(permission.getOtherAction()));
        userMeta.put(Constants.HDFS_PERMISSION_STICKY_KEY, UFileUtils.EncodeFsSticky(permission.getStickyBit()));
        userMeta.put(Constants.HDFS_REPLICATION_NUM_KEY, UFileUtils.EncodeReplication(replication));
        userMeta.put(Constants.HDFS_BLOCK_SIZE_KEY, UFileUtils.EncodeBlockSize(blockSize));
        if (defOwner == null) userMeta.put(Constants.HDFS_OWNER_KEY, username);
        else userMeta.put(Constants.HDFS_OWNER_KEY, defOwner);

        if (defOwner == null) userMeta.put(Constants.HDFS_GROUP_KEY, Constants.superGroup);
        else userMeta.put(Constants.HDFS_GROUP_KEY, defGroup);
        if (crc32c != null) userMeta.put(Constants.HDFS_CHECKSUM_KEY, crc32c);
        if (base64Md5 != null) userMeta.put(Constants.META_MD5_HASH, base64Md5);
        return userMeta;
    }

    @Override
    public boolean setReplication(Path src, short replication) throws IOException {
        UFileUtils.Debug(cfg.getLogLevel(),"[setReplication] f:%s' replication:%d ", src, replication);
        OSMeta osMeta = UFileUtils.ParserPath(uri, workDir, src);
        UFileFileStatus ufs = innerGetFileStatus(src);

        if (ufs.isDirectory()) {
            UFileUtils.Info(cfg.getLogLevel(),"[setReplication] src:%s is directory", src);
            return false;
        }

        String userName = ufs.getOverrideGroupName();
        String groupName = ufs.getOverrideGroupName();
        if (userName == null || userName.equals("")) userName = ufs.getOwner();
        if (groupName == null || groupName.equals("")) groupName = ufs.getGroup();
        Map<String, String> userMeta = extractUserMeta(userName,
                groupName,
                ufs.getHexCrc32c(),
                ufs.getPermission(),
                ufs.getBlockSize(),
                replication,
                ufs.getBase64Md5());
        replaceUserMeta(osMeta, ufs, userMeta);
        return true;
    }

    private boolean replaceUserMeta(OSMeta osMeta, UFileFileStatus ufs,
                                    Map<String, String> userMeta ) throws IOException {
        innerCopyFile(osMeta, osMeta, "REPLACE", userMeta);
        if (ufs.isDirectory()) {
            String key = osMeta.getKey();
            if (key.endsWith("/")) {
                osMeta.setKey(key.substring(key.length()-1));
            } else {
                osMeta.setKey(key+"/");
            }
            innerCopyFile(osMeta, osMeta, "REPLACE", userMeta);
        }
        return true;
    }

    private synchronized static Map<String, String> getDefaultUserMeta() {
        if (defaultUserMeta == null) {
            defaultUserMeta = new HashMap<>();
            defaultUserMeta.put(Constants.HDFS_PERMISSION_STICKY_KEY, "false");
            defaultUserMeta.put(Constants.HDFS_PERMISSION_USER_KEY, Constants.HDFS_FILE_READ_WRITE);
            defaultUserMeta.put(Constants.HDFS_PERMISSION_GROUP_KEY, Constants.HDFS_FILE_READ_WRITE);
            defaultUserMeta.put(Constants.HDFS_PERMISSION_OTHER_KEY, Constants.HDFS_FILE_READ_WRITE);
        }
        return defaultUserMeta;
    }

    @Override
    public long getDefaultBlockSize() {
        UFileUtils.Debug(cfg.getLogLevel(),"[getDefaultBlockSize] default");
        return Constants.DEFAULT_HDFS_BLOCK_SIZE;
    }

    @Override
    public long getDefaultBlockSize(Path f) {
        UFileUtils.Debug(cfg.getLogLevel(),"[getDefaultBlockSize] get %s status", f.toString());
        try {
            UFileFileStatus ufs = innerGetFileStatus(f);
            if (ufs == null) { return Constants.DEFAULT_HDFS_BLOCK_SIZE; }
            else { return ufs.getBlockSize(); }
        } catch (IOException e) {
            UFileUtils.Error(cfg.getLogLevel(),"[getDefaultBlockSize] get %s status,  %s ", f.toString(), e.toString());
            return Constants.DEFAULT_HDFS_BLOCK_SIZE;
        }
    }

    public Configure getCfg() { return cfg; }

    public UfileObjectLocalAuthorization getAuth() { return objauth;}
    public ObjectConfig getMdsCfg() { return objcfg;}

    protected ObjectListWithDirFormatBean listObjects(ObjectListRequest request) throws UfileServerException, UfileClientException {
        /**
         * 如果带有delimiter参数，返回值将区分目录和对象，目录放在commonPrefixes属性中，且不会返回下层目录的内容
         * 如果不带delimiter参数，返回内容不区分目录，目录将以对象形式返回，内容都在objectList属性中，没有层级限制，会返回所有下层目录的内容
         */
        return UfileClient.object(objauth, objcfg)
                .objectListWithDirFormat(request.getBucketName())
                .withPrefix(request.getPrefix())
                .withDelimiter(request.getDelimiter())
                .dataLimit(request.getLimit())
                .execute();
    }

    protected ObjectListWithDirFormatBean continueListObjects(ObjectListWithDirFormatBean previousObjectListBean)
            throws UfileServerException, UfileClientException {
        return UfileClient.object(objauth, objcfg)
                .objectListWithDirFormat(previousObjectListBean.getBucketName())
                .withPrefix(previousObjectListBean.getPrefix())
                .withMarker(previousObjectListBean.getNextMarker())
                .withDelimiter(previousObjectListBean.getDelimiter())
                .execute();
    }

    private void blockRootDelete(String key) throws InvalidRequestException {
        if (key.isEmpty() || "/".equals(key)) {
            throw new InvalidRequestException(Constants.CANNOT_DELETE_ROOT);
        }
    }

    private void copyFile(String srcKey, String dstKey) throws UfileServerException, UfileClientException {
        UFileUtils.Debug(cfg.getLogLevel(),"[copyFile] srcKey:%s' dstKey:%s ", srcKey, dstKey);
        ObjectProfile srcObjProfile = getObjectProfile(srcKey);
        if (Constants.DEFAULT_MULTIPART_COPY_THRESHOLD > srcObjProfile.getContentLength()) {
            UfileClient.object(objauth, objcfg)
                    .copyObject(bucket, srcKey)
                    .copyTo(bucket, dstKey)
                    .execute();
        } else {
            UFileUtils.Debug(cfg.getLogLevel(),"[copyFile] file size: %d", srcObjProfile.getContentLength());
            // 使用分片接口进行拷贝
            MultiUploadInfo uploadInfo = UfileClient.object(objauth, objcfg)
                    // 这里的 uploadTarget是 dstObject
                    .initMultiUpload(dstKey, srcObjProfile.getContentType(), srcObjProfile.getBucket())
                    .withStorageType(srcObjProfile.getStorageType())
                    .withMetaDatas(srcObjProfile.getMetadatas())
                    .execute();
            // 需要先转成double 否则会丢失精度
            int chunkCount = (int) Math.ceil((double) srcObjProfile.getContentLength() / MULTIPART_SIZE);
            // 优化：使用线程池进行同步上传
            List<MultiUploadPartState> partStateList = new ArrayList<>();
            try {
                for (int i = 0; i < chunkCount; i++) {
                    int start = i * MULTIPART_SIZE;
                    int end = start + MULTIPART_SIZE - 1;
                    if (end >= srcObjProfile.getContentLength()) {
                        end = (int) srcObjProfile.getContentLength();
                    }
                    MultiUploadPartState partState = UfileClient.object(objauth, objcfg)
                            .multiUploadCopyPart(uploadInfo, i, srcObjProfile.getBucket(), srcObjProfile.getKeyName(),
                                    start, end)
                            .execute();
                    partStateList.add(partState);
                    UFileUtils.Debug(cfg.getLogLevel(),"[copyFile] part state: %s, start: %d, end: %d", partState.toString(), start, end);
                }
                while(true) {
                    MultiUploadResponse res = UfileClient.object(objauth, objcfg)
                            .finishMultiUpload(uploadInfo, partStateList)
                            .execute();
                    if (res.getFileSize() >= srcObjProfile.getContentLength()) {
                        // 文件上传完毕
                        break;
                    }
                    try {
                        Thread.sleep(Constants.LIST_TRY_DELAY_BASE_TIME);
                    } catch (InterruptedException e) {
                        UFileUtils.Debug(cfg.getLogLevel(),"[copyFile] interrupted when waiting MultiCopy finish: %s", e.getMessage(), e);
                        break;
                    }
                }
            } catch (UfileServerException | UfileClientException e) {
                // copy过程失败后要进行abort
                UfileClient.object(objauth, objcfg)
                        .abortMultiUpload(uploadInfo)
                        .execute();
                throw e;
            }
        }
    }

    protected ObjectProfile getObjectProfile(String key) throws UfileServerException, UfileClientException {
        return UfileClient.object(objauth, objcfg)
                .objectProfile(key, bucket)
                .execute();
    }

    /**
     * Qualify a path.
     * @param path path to qualify
     * @return a qualified path.
     */
    Path qualify(Path path) {
        return path.makeQualified(uri, workDir);
    }

    /**
     * Turns a path (relative or otherwise) into an US3 key.
     *
     * @param path input path, may be relative to the working dir
     * @return a key excluding the leading "/", or, if it is the root path, ""
     */
    private String pathToKey(Path path) {
        if (!path.isAbsolute()) {
            path = new Path(workDir, path);
        }

        if (path.toUri().getScheme() != null && path.toUri().getPath().isEmpty()) {
            return "";
        }

        return path.toUri().getPath().substring(1);
    }

    private long parseStringDateToLong(String date) {
        try {
            return Constants.GMTDateTemplate.parse(date).getTime();
        } catch (ParseException e) {
            UFileUtils.Debug(cfg.getLogLevel(),"[parseStringDateToLong] parse failed date:%s error:%s", date, e.getMessage());
            return 0L;
        }
    }

    /**
     * Predicate: does the object represent a directory?.
     * @param name object name
     * @param size object size
     * @return true if it meets the criteria for being an object
     */
    public static boolean objectRepresentsDirectory(final String name,
                                                    final long size) {
        return !name.isEmpty()
                && name.charAt(name.length() - 1) == '/'
                && size == 0L;
    }

    /**
     * Turns a path (relative or otherwise) into an S3 key, adding a trailing
     * "/" if the path is not the root <i>and</i> does not already have a "/"
     * at the end.
     *
     * @param key s3 key or ""
     * @return the with a trailing "/", or, if it is the root key, "",
     */
    private String maybeAddTrailingSlash(String key) {
        if (!key.isEmpty() && !key.endsWith("/")) {
            return key + '/';
        } else {
            return key;
        }
    }
}
