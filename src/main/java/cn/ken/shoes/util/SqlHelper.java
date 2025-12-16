package cn.ken.shoes.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.function.Function;

@Slf4j
@Component
public class SqlHelper implements ApplicationContextAware {

    private static SqlSessionFactory sqlSessionFactory;

    public static <T> void batch(List<T> entityList, Function<T, Integer> function) {
        if (CollectionUtils.isEmpty(entityList)) {
            return;
        }
        int cnt = 0;
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            for (T entity : entityList) {
                cnt += function.apply(entity);
            }
            sqlSession.commit();
        }
//        log.info("SqlHelper|batch finish, target:{}, actual:{}", entityList.size(), cnt);
    }

    public static <T> int batchWithResult(List<T> entityList, Function<T, Integer> function) {
        if (CollectionUtils.isEmpty(entityList)) {
            return 0;
        }
        int cnt = 0;
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            for (T entity : entityList) {
                cnt += function.apply(entity);
            }
            sqlSession.commit();
        }
        return cnt;
//        log.info("SqlHelper|batch finish, target:{}, actual:{}", entityList.size(), cnt);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        sqlSessionFactory = applicationContext.getBean(SqlSessionFactory.class);
    }
}
