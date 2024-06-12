package ru.stepup.geometry;

import ru.stepup.geometry.annotation.Cache;
import ru.stepup.geometry.annotation.Mutator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.*;

public class FractionInvocationHandler implements InvocationHandler {

    private Fractionable fraction;
    private Double doubleValCash;   // кэшируемое значение дроби
    private long default_timeout = 1000000;    // время для очищения истории в кэше - переустановится при чтении из @Cache(timeout=1000)
    // история кэша, кот очищается планировщиком по истечении времени жизки
    // ключ - время сохраниея, значение - объект с дробью на момент сохранения из кэша + временем последнего обращения
    private volatile ConcurrentHashMap<Long, DataLive> historyMap = new ConcurrentHashMap<Long, DataLive>();
    // планировщик для очищения кэша
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread th = new Thread(r);
            th.setDaemon(true);
            return th;
        }
    });

     public FractionInvocationHandler(Fractionable fraction) {
        this.fraction = fraction;

        // получим значение интервала хранения кэша из аннототации @Cache(timeout=1000)
        Class clazz = this.fraction.getClass();
        Class[] params = null;
         try {
             Method method = clazz.getMethod("doubleValue", params);
             if (method.isAnnotationPresent(Cache.class)) {
                 Cache cache = method.getAnnotation(Cache.class);
                 default_timeout = cache.timeout();
             }
         } catch (NoSuchMethodException e) {
             throw new RuntimeException(e);
         }

         // задаем алгоритм очищения в планировщике по времени
         scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                long current = System.currentTimeMillis();
                for (long k : historyMap.keySet()) {
                    // isLive удаляем только тех у кого не обновлялся параметр DataLiveю.lastTime (последнее обращение)
                    if (historyMap.get(k).isLive(current, default_timeout))
                        historyMap.remove(k);
                    }
                }
        }, 1, default_timeout, TimeUnit.MILLISECONDS);
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        Class clazz = this.fraction.getClass();
        Method md = clazz.getMethod(method.getName(), method.getParameterTypes());

        if (md.isAnnotationPresent(Cache.class)) {
            Cache ta = md.getAnnotation(Cache.class);

            // есть аннотация "Cache" -> получаем значение из кэша, но если он пустой - значала заполняем его
            if (doubleValCash == null) {
                // кэш пустой - заполним его
                this.doubleValCash = (Double) method.invoke(this.fraction, args);
            }
            return this.doubleValCash;
        }

        if (md.isAnnotationPresent(Mutator.class)) {
            // вызван метод с аннотацией Mutator - очищаем кэшируемое значение дроби, а старое значение заносим в кэш истории (historyMap)
            if (this.doubleValCash != null) {
                historyMap.put(System.currentTimeMillis(), new DataLive(this.doubleValCash, System.currentTimeMillis())); // сохранием очищаемое значение в кэше истории
                this.doubleValCash = null;  // очищаем значение кэша с дробью
            }
        }

        return method.invoke(this.fraction, args);
    }
    // печать истории кэша: время сохранения в истории/значение
    public int printtCash() {

        DateFormat dateFormat = new SimpleDateFormat("dd.mm.yyyy HH:mm:ss:SSS") ;
        DecimalFormat df = new DecimalFormat("#.###");

        if (historyMap.isEmpty()) {
             System.out.println("   Изстория кэша пустая");
         }
         else {
             System.out.println("   История кэша (время сохранени/значение):");
             for (long k : historyMap.keySet()) {
                 System.out.println("    Time = " + dateFormat.format(new Date(k)) + "; Value = " + df.format(historyMap.get(k).getDoubleValCash()));
                }}

         return historyMap.size();
    }

    // продление жизни кэша текущем временем
    // ТЗ: Для кэшированных значений указывается время жизни. Если за время жизни значение не было востребовано ни разу,
    // то оно удаляется из кэша. При востребовании значения из кэша, его срок жизни обновляется
    public void cacheReclaiming() {
        // обновляем время обращения к данным кэша в истории
        for (long k : historyMap.keySet()) {
            historyMap.get(k).setLastTime(System.currentTimeMillis());   // меняем время жизни кэша на текущее
        }
    }
}
