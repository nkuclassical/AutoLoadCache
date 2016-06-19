package com.jarvis.cache;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.Logger;

import com.jarvis.cache.annotation.Cache;
import com.jarvis.cache.annotation.CacheDelete;
import com.jarvis.cache.annotation.CacheDeleteKey;
import com.jarvis.cache.annotation.ExCache;
import com.jarvis.cache.aop.CacheAopProxyChain;
import com.jarvis.cache.aop.DeleteCacheAopProxyChain;
import com.jarvis.cache.script.AbstractScriptParser;
import com.jarvis.cache.serializer.ISerializer;
import com.jarvis.cache.to.AutoLoadConfig;
import com.jarvis.cache.to.AutoLoadTO;
import com.jarvis.cache.to.CacheKeyTO;
import com.jarvis.cache.to.CacheWrapper;
import com.jarvis.cache.to.ProcessingTO;
import com.jarvis.cache.type.CacheOpType;

/**
 * 缓存管理抽象类
 * @author jiayu.qiu
 */
public abstract class AbstractCacheManager implements ICacheManager {

    private static final Logger logger=Logger.getLogger(AbstractCacheManager.class);

    // 解决java.lang.NoSuchMethodError:java.util.Map.putIfAbsent
    private final ConcurrentHashMap<String, ProcessingTO> processing=new ConcurrentHashMap<String, ProcessingTO>();

    private final AutoLoadHandler autoLoadHandler;

    private String namespace;

    /**
     * 序列化工具，默认使用Hessian2
     */
    private final ISerializer<Object> serializer;

    /**
     * 表达式解析器
     */
    private final AbstractScriptParser scriptParser;

    /**
     * 刷新缓存线程池
     */
    private final ThreadPoolExecutor refreshThreadPool;

    /**
     * 正在刷新缓存队列
     */
    private final ConcurrentHashMap<String, Byte> refreshing;

    public AbstractCacheManager(AutoLoadConfig config, ISerializer<Object> serializer, AbstractScriptParser scriptParser) {
        autoLoadHandler=new AutoLoadHandler(this, config);
        this.serializer=serializer;
        this.scriptParser=scriptParser;
        registerFunction(config.getFunctions());
        int corePoolSize=2;// 线程池的基本大小
        int maximumPoolSize=20;// 线程池最大大小,线程池允许创建的最大线程数。如果队列满了，并且已创建的线程数小于最大线程数，则线程池会再创建新的线程执行任务。值得注意的是如果使用了无界的任务队列这个参数就没什么效果。
        int keepAliveTime=10;
        TimeUnit unit=TimeUnit.MINUTES;
        int queueCapacity=2000;// 队列容量
        refreshing=new ConcurrentHashMap<String, Byte>(queueCapacity);
        LinkedBlockingQueue<Runnable> queue=new LinkedBlockingQueue<Runnable>(queueCapacity);
        RejectedExecutionHandler rejectedHandler=new RefreshRejectedExecutionHandler();
        refreshThreadPool=new ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, queue, rejectedHandler);
    }

    public ISerializer<Object> getSerializer() {
        return serializer;
    }

    @Override
    public AutoLoadHandler getAutoLoadHandler() {
        return this.autoLoadHandler;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace=namespace;
    }

    public AbstractScriptParser getScriptParser() {
        return scriptParser;
    }

    private void registerFunction(Map<String, String> funcs) {
        if(null == scriptParser) {
            return;
        }
        if(null == funcs || funcs.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, String>> it=funcs.entrySet().iterator();
        while(it.hasNext()) {
            Map.Entry<String, String> entry=it.next();
            try {
                String name=entry.getKey();
                Class<?> cls=Class.forName(entry.getValue());
                Method method=cls.getDeclaredMethod(name, new Class[]{Object.class});
                scriptParser.addFunction(name, method);
            } catch(Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * 处理@Cache 拦截
     * @param pjp 切面
     * @param cache 注解
     * @return T 返回值
     * @throws Exception 异常
     */
    public Object proceed(CacheAopProxyChain pjp, Cache cache) throws Throwable {
        Object[] arguments=pjp.getArgs();
        // Signature signature=pjp.getSignature();
        // MethodSignature methodSignature=(MethodSignature)signature;
        // Class returnType=methodSignature.getReturnType(); // 获取返回值类型
        // System.out.println("returnType:" + returnType.getName());
        if(null != cache.opType() && cache.opType() == CacheOpType.WRITE) {// 更新缓存操作
            CacheWrapper<Object> cacheWrapper=getCacheWrapper(pjp, null, cache, null);
            Object result=cacheWrapper.getCacheObject();
            if(scriptParser.isCacheable(cache, arguments, result)) {
                CacheKeyTO cacheKey=getCacheKey(pjp, cache, result);
                writeCache(pjp, null, cache, cacheKey, cacheWrapper);
            }
            return result;
        }
        if(!scriptParser.isCacheable(cache, arguments)) {// 如果不进行缓存，则直接返回数据
            return getData(pjp);
        }
        CacheKeyTO cacheKey=getCacheKey(pjp, cache);
        if(null == cacheKey) {
            return getData(pjp);
        }
        Type returnType=pjp.getMethod().getGenericReturnType();
        CacheWrapper<Object> cacheWrapper=null;
        try {
            cacheWrapper=this.get(cacheKey, returnType);// 从缓存中获取数据
        } catch(Exception ex) {

        }
        if(null != cacheWrapper && !cacheWrapper.isExpired()) {
            AutoLoadTO autoLoadTO=getAutoLoadTO(pjp, arguments, cache, cacheKey, cacheWrapper);
            if(null != autoLoadTO) {// 同步最后加载时间
                autoLoadTO.setLastRequestTime(System.currentTimeMillis());
                autoLoadTO.setLastLoadTime(cacheWrapper.getLastLoadTime());
                autoLoadTO.setExpire(cacheWrapper.getExpire());// 同步过期时间
            } else {// 如果缓存快要失效，则自动刷新
                doRefresh(pjp, cache, cacheKey, cacheWrapper);
            }
            return cacheWrapper.getCacheObject();
        }
        return loadData(pjp, null, cacheKey, cache);// 从DAO加载数据
    }

    private void doRefresh(CacheAopProxyChain pjp, Cache cache, CacheKeyTO cacheKey, CacheWrapper<Object> cacheWrapper) {
        int expire=cacheWrapper.getExpire();
        if(expire < 60) {// 如果过期时间太小了，就不允许自动加载，避免加载过于频繁，影响系统稳定性
            return;
        }
        // 计算超时时间
        int alarmTime=cache.alarmTime();
        long timeout;
        if(alarmTime > 0 && alarmTime < expire) {
            timeout=expire - alarmTime;
        } else {
            if(expire >= 600) {
                timeout=expire - 120;
            } else {
                timeout=expire - 60;
            }
        }
        if((System.currentTimeMillis() - cacheWrapper.getLastLoadTime()) < (timeout * 1000)) {
            return;
        }
        String fullKey=cacheKey.getFullKey();
        Byte tmpByte=refreshing.get(fullKey);
        if(null != tmpByte) {// 如果有正在刷新的请求，则不处理
            return;
        }
        tmpByte=1;
        if(null == refreshing.putIfAbsent(fullKey, tmpByte)) {
            try {
                refreshThreadPool.execute(new RefreshTask(pjp, cacheKey, cacheWrapper));
            } catch(Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * 处理@CacheDelete 拦截
     * @param jp 切点
     * @param cacheDelete 拦截到的注解
     * @param retVal 返回值
     */
    public void deleteCache(DeleteCacheAopProxyChain jp, CacheDelete cacheDelete, Object retVal) {
        Object[] arguments=jp.getArgs();
        CacheDeleteKey[] keys=cacheDelete.value();
        if(null == keys || keys.length == 0) {
            return;
        }
        for(int i=0; i < keys.length; i++) {
            CacheDeleteKey keyConfig=keys[i];
            try {
                if(!scriptParser.isCanDelete(keyConfig, arguments, retVal)) {
                    continue;
                }
                CacheKeyTO key=getCacheKey(jp, keyConfig, retVal);
                if(null != key) {
                    this.delete(key);
                }
            } catch(Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * 通过CacheAopProxyChain加载数据
     * @param pjp CacheAopProxyChain
     * @param autoLoadTO AutoLoadTO
     * @param cacheKey CacheKeyTO
     * @param cache Cache
     * @return 返回值
     * @throws Throwable 异常
     */
    @Override
    public Object loadData(CacheAopProxyChain pjp, AutoLoadTO autoLoadTO, CacheKeyTO cacheKey, Cache cache) throws Throwable {
        String fullKey=cacheKey.getFullKey();
        ProcessingTO isProcessing=processing.get(fullKey);
        ProcessingTO processingTO=null;
        if(null == isProcessing) {
            processingTO=new ProcessingTO();
            ProcessingTO _isProcessing=processing.putIfAbsent(fullKey, processingTO);// 为发减少数据层的并发，增加等待机制。
            if(null != _isProcessing) {
                isProcessing=_isProcessing;// 获取到第一个线程的ProcessingTO 的引用，保证所有请求都指向同一个引用
            }
        }
        Object lock=null;
        CacheWrapper<Object> cacheWrapper=null;
        // String tname=Thread.currentThread().getName();
        if(null == isProcessing) {// 当前并发中的第一个请求
            lock=processingTO;
            try {
                // System.out.println(tname + " first thread!");
                cacheWrapper=getCacheWrapper(pjp, autoLoadTO, cache, cacheKey);
                writeCache(pjp, autoLoadTO, cache, cacheKey, cacheWrapper);
                processingTO.setCache(cacheWrapper);// 本地缓存
            } catch(Throwable e) {
                processingTO.setError(e);
                throw e;
            } finally {
                processingTO.setFirstFinished(true);
                processing.remove(fullKey);
                synchronized(lock) {
                    lock.notifyAll();
                }
            }
        } else {
            lock=isProcessing;
            long startWait=isProcessing.getStartTime();
            do {// 等待
                if(null == isProcessing) {
                    break;
                }
                if(isProcessing.isFirstFinished()) {
                    cacheWrapper=isProcessing.getCache();// 从本地缓存获取数据， 防止频繁去缓存服务器取数据，造成缓存服务器压力过大
                    // System.out.println(tname + " do FirstFinished" + " is null :" + (null == cacheWrapper));
                    if(null != cacheWrapper) {
                        return cacheWrapper.getCacheObject();
                    }
                    Throwable error=isProcessing.getError();
                    if(null != error) {// 当DAO出错时，直接抛异常
                        // System.out.println(tname + " do error");
                        throw error;
                    }
                    break;
                } else {
                    synchronized(lock) {
                        // System.out.println(tname + " do wait");
                        try {
                            lock.wait(50);// 如果要测试lock对象是否有效，wait时间去掉就可以
                        } catch(InterruptedException ex) {
                            logger.error(ex.getMessage(), ex);
                        }
                    }
                }
            } while(System.currentTimeMillis() - startWait < cache.waitTimeOut());
            try {
                cacheWrapper=getCacheWrapper(pjp, autoLoadTO, cache, cacheKey);
                writeCache(pjp, autoLoadTO, cache, cacheKey, cacheWrapper);
            } catch(Throwable e) {
                throw e;
            } finally {
                synchronized(lock) {
                    lock.notifyAll();
                }
            }
        }
        if(null != cacheWrapper) {
            return cacheWrapper.getCacheObject();
        }
        return null;
    }

    /**
     * 直接加载数据（加载后的数据不往缓存放）
     * @param pjp CacheAopProxyChain
     * @return Object
     * @throws Throwable
     */
    private Object getData(CacheAopProxyChain pjp) throws Throwable {
        try {
            long startTime=System.currentTimeMillis();
            Object[] arguments=pjp.getArgs();
            Object result=pjp.doProxyChain(arguments);
            long useTime=System.currentTimeMillis() - startTime;
            AutoLoadConfig config=autoLoadHandler.getConfig();
            if(config.isPrintSlowLog() && useTime >= config.getSlowLoadTime()) {
                String className=pjp.getTargetClass().getName();
                logger.error(className + "." + pjp.getMethod().getName() + ", use time:" + useTime + "ms");
            }
            return result;
        } catch(Throwable e) {
            throw e;
        }
    }

    /**
     * 加载数据（加载后的数据需要往缓存放）
     * @param pjp CacheAopProxyChain
     * @param autoLoadTO AutoLoadTO
     * @param cache Cache
     * @param cacheKey CacheKeyTO
     * @return CacheWrapper
     * @throws Throwable
     */
    private CacheWrapper<Object> getCacheWrapper(CacheAopProxyChain pjp, AutoLoadTO autoLoadTO, Cache cache, CacheKeyTO cacheKey)
        throws Throwable {
        try {
            long startTime=System.currentTimeMillis();
            Object[] arguments;
            if(null == autoLoadTO) {
                arguments=pjp.getArgs();
            } else {
                arguments=autoLoadTO.getArgs();
                autoLoadTO.setLoading(true);
            }
            Object result=pjp.doProxyChain(arguments);
            long useTime=System.currentTimeMillis() - startTime;
            AutoLoadConfig config=autoLoadHandler.getConfig();
            if(config.isPrintSlowLog() && useTime >= config.getSlowLoadTime()) {
                String className=pjp.getTargetClass().getName();
                logger.error(className + "." + pjp.getMethod().getName() + ", use time:" + useTime + "ms");
            }
            int expire=scriptParser.getRealExpire(cache.expire(), cache.expireExpression(), arguments, result);
            CacheWrapper<Object> cacheWrapper=new CacheWrapper<Object>(result, expire);
            if(null != cacheKey && null == autoLoadTO) {
                autoLoadTO=getAutoLoadTO(pjp, arguments, cache, cacheKey, cacheWrapper);
                if(null != autoLoadTO) {// 只有当autoLoadTO时才是实际用户请求，不为null时，是AutoLoadHandler 发过来的请求
                    autoLoadTO.setLastRequestTime(startTime);
                }
            }
            if(null != autoLoadTO) {
                autoLoadTO.setLastLoadTime(startTime);
                autoLoadTO.addUseTotalTime(useTime);
            }
            return cacheWrapper;
        } catch(Throwable e) {
            throw e;
        } finally {
            if(null != autoLoadTO) {
                autoLoadTO.setLoading(false);
            }
        }
    }

    /**
     * 写缓存
     * @param pjp CacheAopProxyChain
     * @param autoLoadTO AutoLoadTO
     * @param cache Cache annotation
     * @param cacheKey Cache Key
     * @param cacheWrapper CacheWrapper
     * @return CacheWrapper
     * @throws Exception
     */
    private CacheWrapper<Object> writeCache(CacheAopProxyChain pjp, AutoLoadTO autoLoadTO, Cache cache, CacheKeyTO cacheKey,
        CacheWrapper<Object> cacheWrapper) throws Exception {
        if(null == cacheKey) {
            return null;
        }
        this.setCache(cacheKey, cacheWrapper);

        ExCache[] exCaches=cache.exCache();
        if(null != exCaches && exCaches.length > 0) {
            Object[] arguments=pjp.getArgs();
            if(null != autoLoadTO) {
                arguments=autoLoadTO.getArgs();
            }
            Object result=cacheWrapper.getCacheObject();
            for(ExCache exCache: exCaches) {
                if(!scriptParser.isCacheable(exCache, arguments, result)) {
                    continue;
                }
                CacheKeyTO exCacheKey=getCacheKey(pjp, autoLoadTO, exCache, result);
                if(null == exCacheKey) {
                    continue;
                }
                Object exResult=null;
                if(null == exCache.cacheObject() || exCache.cacheObject().length() == 0) {
                    exResult=result;
                } else {
                    exResult=scriptParser.getElValue(exCache.cacheObject(), arguments, result, true, Object.class);
                }

                int exCacheExpire=scriptParser.getRealExpire(exCache.expire(), exCache.expireExpression(), arguments, exResult);
                CacheWrapper<Object> exCacheWrapper=new CacheWrapper<Object>(exResult, exCacheExpire);
                AutoLoadTO tmpAutoLoadTO=this.autoLoadHandler.getAutoLoadTO(exCacheKey);
                if(null != tmpAutoLoadTO) {
                    tmpAutoLoadTO.setExpire(exCacheExpire);
                    tmpAutoLoadTO.setLastLoadTime(exCacheWrapper.getLastLoadTime());
                }
                this.setCache(exCacheKey, exCacheWrapper);
            }
        }
        return cacheWrapper;
    }

    @Override
    public void destroy() {
        autoLoadHandler.shutdown();
        refreshThreadPool.shutdownNow();
        logger.info("cache destroy ... ... ...");
    }

    /**
     * 生成缓存KeyTO
     * @param className 类名
     * @param methodName 方法名
     * @param arguments 参数
     * @param _key key
     * @param _hfield hfield
     * @param result 执行实际方法的返回值
     * @return CacheKeyTO
     * @throws Exception
     */
    private CacheKeyTO getCacheKey(String className, String methodName, Object[] arguments, String _key, String _hfield,
        Object result, boolean hasRetVal) throws Exception {
        String key=null;
        String hfield=null;
        if(null != _key && _key.trim().length() > 0) {
            key=scriptParser.getDefinedCacheKey(_key, arguments, result, hasRetVal);
            if(null != _hfield && _hfield.trim().length() > 0) {
                hfield=scriptParser.getDefinedCacheKey(_hfield, arguments, result, hasRetVal);
            }
        } else {
            key=CacheUtil.getDefaultCacheKey(className, methodName, arguments);
        }
        if(null == key || key.trim().length() == 0) {
            logger.error(className + "." + methodName + "; cache key is empty");
            return null;
        }
        CacheKeyTO to=new CacheKeyTO();
        to.setNamespace(namespace);
        to.setKey(key);
        to.setHfield(hfield);
        return to;
    }

    /**
     * 生成缓存 Key
     * @param pjp
     * @param cache
     * @return String 缓存Key
     * @throws Exception
     */
    private CacheKeyTO getCacheKey(CacheAopProxyChain pjp, Cache cache) throws Exception {
        String className=pjp.getTargetClass().getName();
        String methodName=pjp.getMethod().getName();
        Object[] arguments=pjp.getArgs();
        String _key=cache.key();
        String _hfield=cache.hfield();
        return getCacheKey(className, methodName, arguments, _key, _hfield, null, false);
    }

    /**
     * 生成缓存 Key
     * @param pjp
     * @param cache
     * @param result 执行结果值
     * @return 缓存Key
     * @throws Exception
     */
    private CacheKeyTO getCacheKey(CacheAopProxyChain pjp, Cache cache, Object result) throws Exception {
        String className=pjp.getTargetClass().getName();
        String methodName=pjp.getMethod().getName();
        Object[] arguments=pjp.getArgs();
        String _key=cache.key();
        String _hfield=cache.hfield();
        return getCacheKey(className, methodName, arguments, _key, _hfield, result, true);
    }

    /**
     * 生成缓存 Key
     * @param pjp
     * @param cache
     * @param result 执行结果值
     * @return 缓存Key
     * @throws Exception
     */
    private CacheKeyTO getCacheKey(CacheAopProxyChain pjp, AutoLoadTO autoLoadTO, ExCache cache, Object result) throws Exception {
        String className=pjp.getTargetClass().getName();
        String methodName=pjp.getMethod().getName();
        Object[] arguments=pjp.getArgs();
        if(null != autoLoadTO) {
            arguments=autoLoadTO.getArgs();
        }
        String _key=cache.key();
        if(null == _key || _key.trim().length() == 0) {
            return null;
        }
        String _hfield=cache.hfield();
        return getCacheKey(className, methodName, arguments, _key, _hfield, result, true);
    }

    /**
     * 生成缓存 Key
     * @param jp
     * @param cacheDeleteKey
     * @param retVal 执行结果值
     * @return 缓存Key
     * @throws Exception
     */
    private CacheKeyTO getCacheKey(DeleteCacheAopProxyChain jp, CacheDeleteKey cacheDeleteKey, Object retVal) throws Exception {
        String className=jp.getTargetClass().getName();
        String methodName=jp.getMethod().getName();
        Object[] arguments=jp.getArgs();
        String _key=cacheDeleteKey.value();
        String _hfield=cacheDeleteKey.hfield();
        return getCacheKey(className, methodName, arguments, _key, _hfield, retVal, true);

    }

    /**
     * 获取 AutoLoadTO
     * @param pjp
     * @param arguments
     * @param cache
     * @param cacheKey
     * @param cacheWrapper
     * @return
     * @throws Exception
     */
    private AutoLoadTO getAutoLoadTO(CacheAopProxyChain pjp, Object[] arguments, Cache cache, CacheKeyTO cacheKey,
        CacheWrapper<Object> cacheWrapper) throws Exception {
        AutoLoadTO autoLoadTO=null;
        if(scriptParser.isAutoload(cache, arguments, cacheWrapper.getCacheObject())) {
            autoLoadTO=autoLoadHandler.getAutoLoadTO(cacheKey);
            if(null == autoLoadTO) {
                AutoLoadTO tmp=autoLoadHandler.putIfAbsent(cacheKey, pjp, cache, serializer, cacheWrapper);
                if(null != tmp) {
                    autoLoadTO=tmp;
                }
            }
        }
        return autoLoadTO;
    }

    class RefreshTask implements Runnable {

        private CacheAopProxyChain pjp;

        private CacheKeyTO cacheKey;

        private CacheWrapper<Object> cacheWrapper;

        private Object[] arguments;

        public RefreshTask(CacheAopProxyChain pjp, CacheKeyTO cacheKey, CacheWrapper<Object> cacheWrapper) throws Exception {
            this.pjp=pjp;
            this.cacheKey=cacheKey;
            this.cacheWrapper=cacheWrapper;
            Object[] _arguments=pjp.getArgs();
            this.arguments=(Object[])serializer.deepClone(_arguments); // 进行深度复制
        }

        @Override
        public void run() {
            // 加载数据
            try {
                long startTime=System.currentTimeMillis();
                Object result=pjp.doProxyChain(arguments);
                long useTime=System.currentTimeMillis() - startTime;
                AutoLoadConfig config=autoLoadHandler.getConfig();
                if(config.isPrintSlowLog() && useTime >= config.getSlowLoadTime()) {
                    String className=pjp.getTargetClass().getName();
                    logger.error(className + "." + pjp.getMethod().getName() + ", use time:" + useTime + "ms");
                }
                CacheWrapper<Object> tmpWrapper=new CacheWrapper<Object>(result, cacheWrapper.getExpire());
                setCache(cacheKey, tmpWrapper);
            } catch(Throwable e) {
                logger.error(e.getMessage(), e);
                // 加载失败，使用旧数据，并将过期时间减半
                int expire=(int)(cacheWrapper.getExpire() / 2);
                cacheWrapper.setExpire(expire);
                setCache(cacheKey, cacheWrapper);
            } finally {
                String fullKey=cacheKey.getFullKey();
                refreshing.remove(fullKey);
            }
        }

        public CacheKeyTO getCacheKey() {
            return cacheKey;
        }

    }

    class RefreshRejectedExecutionHandler implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if(!e.isShutdown()) {
                Runnable last=e.getQueue().poll();
                if(last instanceof RefreshTask) {
                    RefreshTask lastTask=(RefreshTask)last;
                    String fullKey=lastTask.getCacheKey().getFullKey();
                    refreshing.remove(fullKey);
                }
                e.execute(r);
            }
        }

    }
}
