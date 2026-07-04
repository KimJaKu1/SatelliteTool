package org.sat_tool.domain.common.helper;

import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 경로별 파일 쓰기 직렬화를 위한 고정 크기 스트라이프 락.
 * 경로마다 락 객체를 맵에 무한정 쌓는 방식과 달리 메모리 사용이 상수로 유지된다.
 * 서로 다른 경로가 같은 스트라이프를 공유할 수 있으나(동시성만 소폭 감소) 정확성에는 영향 없다.
 */
public final class PathLocks {

    private static final int STRIPES = 64;
    private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    private PathLocks() {
    }

    public static ReentrantLock forPath(Path path) {
        return LOCKS[Math.floorMod(path.hashCode(), STRIPES)];
    }
}
