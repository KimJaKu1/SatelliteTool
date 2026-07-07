package org.sat_tool.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /** @Async와 JobManager가 공유하는 단일 실행기 */
    @Bean(name = "satToolTaskExecutor")
    public ThreadPoolTaskExecutor satToolTaskExecutor() {
        int processors = Runtime.getRuntime().availableProcessors();
        log.info("async executor: {} processors", processors);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(processors);
        executor.setMaxPoolSize(processors * 2);
        executor.setQueueCapacity(processors * 4);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("sat-async-");
        // 큐 포화 시 작업을 버리지 않고 호출 스레드에서 실행 (backpressure)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return satToolTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        // void @Async 메서드에서 던져진 예외가 조용히 사라지지 않도록 로깅
        return (ex, method, params) ->
                log.error("uncaught exception in @Async method {}", method, ex);
    }
}
