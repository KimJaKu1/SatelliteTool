package org.sat_tool.infra.common;

import com.jcraft.jsch.SftpException;
import lombok.extern.slf4j.Slf4j;
import org.sat_tool.infra.service.FileTransferService;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public abstract class AbstractRemoteClient implements FileTransferService {

    /* ───── 연결 제어 (하위 클래스가 실구현) ───── */
    protected abstract void doConnect()      throws IOException;
    protected abstract void doDisconnect()   throws IOException;
    protected abstract boolean isPhysicallyConnected();

    @Override public final boolean connect() throws IOException { doConnect(); return isConnected(); }
    @Override public final boolean isConnected() { return isPhysicallyConnected(); }
    @Override public final void disconnect()     { try { doDisconnect(); } catch (IOException ignore) {} }
    @Override public final void close()          { disconnect(); }

    /* ───── I/O Hook 4종 (하위 클래스가 실구현) ───── */
    protected abstract InputStream openRemoteInput(String remotePath)
            throws IOException, SftpException;
    protected abstract void putRemote(String remotePath, InputStream in)
            throws IOException, SftpException;
    protected abstract void deleteRemote(String remotePath)
            throws IOException, SftpException;
    protected abstract void ensureRemoteDirExists(String remoteDir)
            throws IOException, SftpException;

    /* ───── 고수준 공통 구현 ───── */


    @Override
    public void download(List<String> remoteFiles, String localDir, boolean remove)
            throws IOException, SftpException {
        if (!isConnected() || remoteFiles == null || remoteFiles.isEmpty()) return;

        Path dir = Paths.get(localDir);
        if (Files.notExists(dir)) Files.createDirectories(dir);

        for (String rf : remoteFiles) {
            Path target = dir.resolve(Path.of(rf).getFileName());
            try (InputStream in  = openRemoteInput(rf);
                 OutputStream out = Files.newOutputStream(target)) {
                in.transferTo(out);
            }
            if (remove) deleteRemote(rf);
            log.info("[DOWNLOAD] {} → {}", rf, target);
        }
    }

    @Override
    public void upload(List<File> files, String remoteDir, boolean remove)
            throws IOException, SftpException {
        if (!isConnected() || files == null || files.isEmpty()) return;
        ensureRemoteDirExists(remoteDir);

        for (File f : files) {
            String dst = (remoteDir.endsWith("/") ? remoteDir : remoteDir + "/") + f.getName();
            try (InputStream in = new FileInputStream(f)) {
                putRemote(dst, in);
            }
            if (remove && !f.delete()) log.warn("Cannot deleteById local file {}", f);
            log.info("[UPLOAD] {} → {}", f, dst);
        }
    }

    @Override
    public void delete(List<String> remoteFiles)
            throws IOException, SftpException {
        if (!isConnected() || remoteFiles == null) return;
        for (String rf : remoteFiles) deleteRemote(rf);
    }

    @Override
    public void move(List<String> remoteFiles, String targetDir)
            throws IOException, SftpException {
        copy(remoteFiles, targetDir);
        delete(remoteFiles);
    }

    @Override
    public void copy(List<String> remoteFiles, String targetDir)
            throws IOException, SftpException {
        if (!isConnected() || remoteFiles == null || remoteFiles.isEmpty()) return;
        ensureRemoteDirExists(targetDir);

        Path tmp = Files.createTempDirectory("ftCopy");
        try {
            download(remoteFiles, tmp.toString(), false);               // ① 원격 → 로컬 tmp
            List<File> locals = Files.list(tmp).map(Path::toFile).collect(Collectors.toList());
            upload(locals, targetDir, true);                             // ② 로컬 tmp → 원격
        } finally {                                                      // ③ tmp 정리
            Files.walk(tmp).sorted((a,b)->-a.compareTo(b))
                    .forEach(p -> p.toFile().delete());
        }
    }
}