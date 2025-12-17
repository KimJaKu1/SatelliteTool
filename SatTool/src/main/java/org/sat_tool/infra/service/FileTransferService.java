package org.sat_tool.infra.service;

import java.io.File;
import java.io.IOException;
import java.util.List;

import com.jcraft.jsch.SftpException;
import org.apache.commons.net.ftp.FTPFile;

public interface FileTransferService extends AutoCloseable {

    /* ───── 연결 ───── */
    boolean connect() throws IOException;
    boolean isConnected();
    void disconnect();

    /* ───── 목록 ───── */
    /** 지정 경로의 ‘파일’만 반환 */
    List<FTPFile> files(String path) throws IOException;
    /** 지정 경로의 ‘디렉터리 이름’만 반환 */
    List<String>  directoryList(String path) throws IOException;

    /* ───── 전송 ───── */
    void download(List<String> remoteFiles, String localDir, boolean remove)
            throws IOException, SftpException;
    void upload  (List<File>   files, String remoteDir, boolean remove)
            throws IOException, SftpException;

    /* ───── 관리 ───── */
    void delete(List<String> remoteFiles)                     throws IOException, SftpException;
    void move  (List<String> remoteFiles, String targetDir)   throws IOException, SftpException;
    void copy  (List<String> remoteFiles, String targetDir)   throws IOException, SftpException;

    @Override void close();
}
