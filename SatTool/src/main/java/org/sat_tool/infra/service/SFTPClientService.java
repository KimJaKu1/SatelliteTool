package org.sat_tool.infra.service;


import com.jcraft.jsch.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPFile;
import org.sat_tool.infra.common.AbstractRemoteClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Vector;
import java.util.stream.Collectors;

@Service
@Slf4j
public final class SFTPClientService extends AbstractRemoteClient {

    private String host;
    private int    port = 22;
    private String user;
    private String pass;
    private Session session;
    private ChannelSftp sftp;

    /* ───── 연결 구현 ───── */
    @Override protected void doConnect() throws IOException {
        try {
            session = new JSch().getSession(user, host, port);
            session.setPassword(pass);
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect(3000);

            Channel ch = session.openChannel("sftp");
            ch.connect();
            sftp = (ChannelSftp) ch;
        } catch (JSchException e) { throw new IOException(e); }
    }
    @Override protected void doDisconnect() {
        if (sftp   != null) sftp.disconnect();
        if (session!= null) session.disconnect();
    }
    @Override protected boolean isPhysicallyConnected() { return sftp != null && sftp.isConnected(); }

    public boolean isSameConnection(String host, String user, String pass) {
        return isConnected()
                && this.host.equalsIgnoreCase(host)
                && this.user.equals(user)
                && this.pass.equals(pass);
    }

    public void prepareConnect(String server, String user, String password) {
        if (server == null || server.isBlank()) throw new IllegalArgumentException("server cannot be blank");

        String tmpHost = server;
        int    tmpPort = 22;                          // SFTP 기본 포트
        if (server.contains(":")) {
            String[] sp = server.split(":");
            tmpHost = sp[0];
            tmpPort = Integer.parseInt(sp[1]);
        }

        if (isSameConnection(tmpHost, user, password)) return;

        this.host = tmpHost;
        this.port = tmpPort;
        this.user = user;
        this.pass = password;
    }

    /* ───── 목록 구현 ───── */
    @Override
    public List<FTPFile> files(String path) throws IOException {
        try {
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> v = sftp.ls(path);
            return v.stream()
                    .filter(e -> !e.getAttrs().isDir())
                    .map(SFTPClientService::toFtpFile)
                    .sorted(Comparator.comparing(FTPFile::getName, String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());
        } catch (SftpException e) { throw new IOException(e); }
    }

    @Override
    public List<String> directoryList(String path) throws IOException {
        try {
            @SuppressWarnings("unchecked")
            Vector<ChannelSftp.LsEntry> v = sftp.ls(path);
            return v.stream()
                    .filter(e -> e.getAttrs().isDir())
                    .map(ChannelSftp.LsEntry::getFilename)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        } catch (SftpException e) { throw new IOException(e); }
    }

    private static FTPFile toFtpFile(ChannelSftp.LsEntry e) {
        FTPFile f = new FTPFile();
        f.setType(FTPFile.FILE_TYPE);
        f.setName(e.getFilename());
        f.setSize(e.getAttrs().getSize());

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(e.getAttrs().getMTime() * 1000L);
        f.setTimestamp(cal);
        return f;
    }

    /* ───── I/O Hook 4종 ───── */
    @Override
    protected InputStream openRemoteInput(String remote)
            throws IOException, SftpException {
        return sftp.get(remote);          // 그대로 전파
    }

    @Override
    protected void putRemote(String remote, InputStream in)
            throws IOException, SftpException {
        sftp.put(in, remote);
    }

    @Override
    protected void deleteRemote(String remote)
            throws IOException, SftpException {
        sftp.rm(remote);
    }

    @Override
    protected void ensureRemoteDirExists(String dir)
            throws IOException, SftpException {
        String[] parts = dir.split("/");
        String cur = "";
        for (String p : parts) {
            if (p.isBlank()) continue;
            cur += "/" + p;
            try { sftp.cd(cur); }                     // 존재하면 이동
            catch (SftpException e) {                // 없으면 생성 후 이동
                sftp.mkdir(cur); sftp.cd(cur);
            }
        }
        sftp.cd("/");                                // 작업 디렉터리 원복
    }
}