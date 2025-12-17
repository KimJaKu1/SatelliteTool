package csg.csg_back_pro.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
        int processors=Runtime.getRuntime().availableProcessors();
        log.info("processors size {}",processors);
        executor.setCorePoolSize(processors);
        executor.setMaxPoolSize(processors*2);
        executor.setQueueCapacity(50);
        executor.setKeepAliveSeconds(60);
        executor.setThreadGroupName("asyncConfig Check");
        executor.initialize();

        return executor;
    }

    @Bean(name = "atPool")
    public Executor atPool() {
        int cores = Runtime.getRuntime().availableProcessors(); // i7-12700 ≒ 20
        // CPU 60 ~ 70 %만 쓰도록 제한
        int poolSize   = Math.min(cores, 12);
        int maxPool    = Math.min(cores * 2, 24);

        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(poolSize);
        ex.setMaxPoolSize(maxPool);
        ex.setQueueCapacity(poolSize * 4);   // 짧은 burst 흡수
        ex.setThreadNamePrefix("sat-async-");
        ex.initialize();
        return ex;
    }

    @Bean(name = "csPool")
    public Executor csExecutor() {
        int cores   = Runtime.getRuntime().availableProcessors(); // i7-12700 ≒ 20
        int coreSz  = Math.min(cores, 12);   // 과점유 방지
        int maxSz   = coreSz * 2;            // burst 대응
        int queueSz = coreSz * 4;

        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(coreSz);
        ex.setMaxPoolSize(maxSz);
        ex.setQueueCapacity(queueSz);
        ex.setThreadNamePrefix("cs-");
        ex.initialize();
        return ex;
    }

    @Bean(name = "satPool")     // at·cs 둘 다 사용
    public Executor satExecutor() {
        int cores = Runtime.getRuntime().availableProcessors();   // 20
        int core  = Math.min(cores, 14);  // 전체 코어의 ≈70 %
        int max   = core * 2;             // 28
        int queue = core * 400;             // 56

        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(core);
        ex.setMaxPoolSize(max);
        ex.setQueueCapacity(queue);
        ex.setThreadNamePrefix("sat-");
        ex.initialize();
        return ex;
    }
}
