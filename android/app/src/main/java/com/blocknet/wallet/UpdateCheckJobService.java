package com.blocknet.wallet;

import android.app.job.JobParameters;
import android.app.job.JobService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** A periodic read-only release check; the checker itself enforces Wi-Fi/mobile policy. */
public final class UpdateCheckJobService extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicInteger generation=new AtomicInteger();
    @Override public boolean onStartJob(JobParameters parameters){
        int request=generation.incrementAndGet();
        worker.execute(()->{
            try { new SoftwareUpdateChecker(this).check(false); } catch(Exception ignored) { }
            if(generation.get()==request)jobFinished(parameters,false);
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters){generation.incrementAndGet();return false;}
    @Override public void onDestroy(){generation.incrementAndGet();worker.shutdownNow();super.onDestroy();}
}
