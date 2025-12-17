package csg.csg_back_pro.infra.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.Map;

@Slf4j
public class Interceptor implements HandlerInterceptor {

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        String method = request.getMethod();
        String uri = request.getRequestURI();
        Map<String,String []> parameterMap = request.getParameterMap();

        if(method.equals("GET")){
            log.info(" {} 로 조회 요청하였습니다",handler.toString());
        }else if(method.equals("POST")){
            log.info(" {}로 {}정보의 등록 요청하였습니다",uri,parameterMap);
        }else if (method.equals("DELETE")) {
            log.info("{}로 삭제 요청하였습니다",uri);
        }else if (method.equals("PUT")){
            log.info("{}로 {}정보 수정 요청하였습니다",uri,parameterMap);
        }
    }

}
