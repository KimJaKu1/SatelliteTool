package org.sat_tool.infra.service;

import com.jcraft.jsch.SftpException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.sat_tool.infra.common.AbstractRemoteClient;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;


@Slf4j
@Service               // Spring Service 빈
public final class FTPClientService extends AbstractRemoteClient {

    private FTPClient ftp = new FTPClient();
    private String host;
//    private int    port = 2121;   // 기본값(FTP) / 22(SFTP)로 초기화
    private int    port = 21;   // 기본값(FTP) / 22(SFTP)로 초기화
    private String user;
    private String pass;

    /* ───── 연결 구현 ───── */
    @Override protected void doConnect() throws IOException {
        ftp.connect(host, port);
//        ftp.sendCommand("AUTH", "TLS");
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode()))
            throw new IOException("FTP connect: " + ftp.getReplyString());
        if (!ftp.login(user, pass))
            throw new IOException("FTP login: " + ftp.getReplyString());

        ftp.enterLocalPassiveMode();
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
    }
    @Override protected void doDisconnect() throws IOException {
        try { if (ftp.isConnected()) ftp.logout(); }
        finally { if (ftp.isConnected()) ftp.disconnect(); }
    }
    @Override protected boolean isPhysicallyConnected() { return ftp.isConnected(); }

    public boolean isSameConnection(String host, String user, String pass) {
        return isConnected()
                && this.host.equalsIgnoreCase(host)
                && this.user.equals(user)
                && this.pass.equals(pass);
    }

    public void prepareConnect(String server, String user, String password) {
        if (server == null || server.isBlank()) throw new IllegalArgumentException("server cannot be blank");

        String tmpHost = server;
        int    tmpPort = 21;                          // FTP 기본 포트
//        int    tmpPort = 2121;                          // FTP 기본 포트
        if (server.contains(":")) {
            String[] sp = server.split(":");
            tmpHost = sp[0];
            tmpPort = Integer.parseInt(sp[1]);
        }

        /* 같은 세션이면 스킵 */
        if (isSameConnection(tmpHost, user, password)) return;

        /* 필드 주입 */
        this.host = tmpHost;
        this.port = tmpPort;
        this.user = user;
        this.pass = password;
    }

    /* ───── 목록 구현 ───── */
    @Override
    public List<FTPFile> files(String path) throws IOException {
        FTPFile[] arr = ftp.listFiles(path);
        if (arr == null) return List.of();
        return Arrays.stream(arr)
                .filter(FTPFile::isFile)
                .sorted(Comparator.comparing(FTPFile::getName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());
    }

    @Override
    public List<String> directoryList(String path) throws IOException {
        FTPFile[] arr = ftp.listFiles(path);
        if (arr == null) return List.of();
        return Arrays.stream(arr)
                .filter(FTPFile::isDirectory)
                .map(FTPFile::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    /* ───── I/O Hook 4종 ───── */
    @Override
    protected InputStream openRemoteInput(String remote)
            throws IOException, SftpException {
        PipedInputStream in = new PipedInputStream();
        new Thread(() -> {
            try (OutputStream os = new PipedOutputStream(in)) {
                ftp.retrieveFile(remote, os);
            } catch (IOException e) { log.error("FTP retrieve", e); }
        }).start();
        return in;
    }

    @Override
    protected void putRemote(String remote, InputStream in)
            throws IOException, SftpException {
        if (!ftp.storeFile(remote, in))
            throw new IOException("FTP store: " + ftp.getReplyString());
    }

    @Override
    protected void deleteRemote(String remote)
            throws IOException, SftpException {
        ftp.deleteFile(remote);
    }

    @Override
    protected void ensureRemoteDirExists(String dir)
            throws IOException, SftpException {
        if (ftp.changeWorkingDirectory(dir)) return;
        String[] parts = dir.split("/");
        String cur = "";
        for (String p : parts) {
            if (p.isBlank()) continue;
            cur += "/" + p;
            ftp.makeDirectory(cur);
        }
    }
}