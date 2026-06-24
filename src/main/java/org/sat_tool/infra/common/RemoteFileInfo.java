package org.sat_tool.infra.common;

import lombok.Data;

import java.util.Date;

/**
 * C# WinSCP의 RemoteFileInfo에 대응하는 자바 DTO 예시
 * - FTPFile, LsEntry 등에서 추출한 파일 정보 보관
 */
@Data
public class RemoteFileInfo {
    private String name;        // 파일명
    private String fullName;    // 전체 경로
    private long length;        // 파일 크기
    private Date lastWriteTime; // 최종 수정 시각
    private boolean directory;  // 디렉토리 여부

    /**
     * 예) Commons Net의 FTPFile -> RemoteFileInfo로 매핑하는 생성자
     */
    public RemoteFileInfo(org.apache.commons.net.ftp.FTPFile ftpFile, String parentPath) {
        this.name = ftpFile.getName();
        // fullName은 단순히 "부모경로 + 파일명" 형태로 가정
        this.fullName = (parentPath.endsWith("/"))
                ? parentPath + ftpFile.getName()
                : parentPath + "/" + ftpFile.getName();

        this.length = ftpFile.getSize();
        // FTPFile의 timestamp -> Calendar -> Date
        if (ftpFile.getTimestamp() != null) {
            this.lastWriteTime = ftpFile.getTimestamp().getTime();
        }
        this.directory = ftpFile.isDirectory();
    }

    /**
     * 기본 생성자(수동 세팅용)
     */
    public RemoteFileInfo() {}
}