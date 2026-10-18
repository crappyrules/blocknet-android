package com.blocknet.wallet;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.json.*;
import java.io.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

/** A framework-native Android wallet. No web content runs inside this activity. */
public final class MainActivity extends Activity {
    private WalletTheme ui;
    private WalletRepository repo;
    private AddressBook addressBook;
    private BiometricVault biometricVault;
    private CancellationSignal biometricCancellation;
    private javax.crypto.Cipher pendingBiometricCipher;
    private BiometricVault.Enrollment pendingBiometricEnrollment;
    private char[] pendingBiometricPassword;
    private volatile int biometricSession;
    private boolean biometricBusy,updatingBiometricSwitch;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService updateWorker=Executors.newSingleThreadExecutor();
    private final ExecutorService bookWorker=Executors.newSingleThreadExecutor();
    private android.net.ConnectivityManager.NetworkCallback networkWatcher;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<AlertDialog> sensitiveDialogs=new HashSet<>(),openDialogs=new HashSet<>();
    private final Runnable autoLock=()->{if(!this.destroyed)worker.execute(()->{try{repo.lock();}catch(Exception ignored){}});};
    private WalletRepository.Snapshot snapshot;
    private LinearLayout shell, body, nav;
    private TextView nodeBadge,balanceText,pendingText,chainText,errorText;
    private final Set<TextView> syncStatusViews=new HashSet<>(),checkpointStatusViews=new HashSet<>(),networkPolicyViews=new HashSet<>();
    private final Set<Switch> mobileDataSwitches=new HashSet<>();
    private final Set<Button> syncRunButtons=new HashSet<>();
    private boolean updatingPolicyControls,updatingAlertControls;
    private final Set<Switch> alertSoundControls=new HashSet<>(),alertEnabledControls=new HashSet<>();
    private ProgressBar loading;
    private Button sendButton;
    private String page="overview",historyFilter="all";
    private boolean busy, rendering, refreshInFlight, refreshPending;
    private boolean updateChecking;
    private boolean bookCheckRunning;
    private volatile int bookCheckEpoch;
    private String updateMessage="";
    private volatile boolean resumed,destroyed;
    private int generation;
    private volatile int privacyEpoch;
    private AlertDialog sendDialog;
    private EditText sendAddress,sendAmount,sendMemo;
    private String selectedBookRecipient="",selectedBookAddress="";
    private long selectedBookRevision;
    private TextView selectedBookHint;
    private ImageView receiveQr;
    private TextView receiveRecipientText,shortnameStatus;
    private EditText preferredShortnameField;
    private Button shortnameSave,shortnameClear,receiveCopy,receiveShare,receiveFullCopy;
    private String receiveEntryKey="",renderedReceiveRecipient="",shortnameNotice="";
    private int receiveVerificationToken;
    private boolean shortnameChecking,receiveForceAddress=true;
    private String paymentText, pendingImportName, historyWalletKey="";
    private JSONArray olderOutputs=new JSONArray(),olderSends=new JSONArray();
    private int outputOffset=100,sendOffset=100;
    private boolean moreOutputs=true,moreSends=true;
    private final Runnable poll=new Runnable(){public void run(){if(resumed&&!destroyed){if(!busy)refresh(false);main.postDelayed(this,5000);}}};
    interface Work<T>{T run() throws Exception;}
    interface Done<T>{void accept(T result) throws Exception;}

    @Override protected void attachBaseContext(Context base){super.attachBaseContext(AppLanguage.wrap(base));}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(WalletTheme.BG);getWindow().setNavigationBarColor(WalletTheme.BG);
        ui=new WalletTheme(this);repo=new WalletRepository(this);addressBook=new AddressBook(this);biometricVault=new BiometricVault(this);
        updateWorker.execute(()->{try{SoftwareUpdateChecker.schedule(this);}catch(RuntimeException ignored){}});
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,()->{if(!page.equals("overview")){page="overview";render();}else finish();});
        if(saved!=null){page=saved.getString("page","overview");pendingImportName=saved.getString("importName");}
        paymentText=saved!=null?saved.getString("payment"):getIntent().getDataString();
        render();refresh(true);
        requestNotificationPermission();
    }
    private void requestNotificationPermission(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},504);
    }
    @Override protected void onStart(){super.onStart();main.removeCallbacks(autoLock);
        android.net.ConnectivityManager manager=getSystemService(android.net.ConnectivityManager.class);
        if(manager!=null){
            networkWatcher=new android.net.ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(android.net.Network network){networkChanged();}
                @Override public void onLost(android.net.Network network){networkChanged();}
                @Override public void onCapabilitiesChanged(android.net.Network network,android.net.NetworkCapabilities capabilities){networkChanged();}
            };
            try{manager.registerDefaultNetworkCallback(networkWatcher);}catch(RuntimeException unavailable){networkWatcher=null;}
        }
    }
    private void networkChanged(){
        ShortnameResolver.cancelDisallowed(this);
        main.post(()->{if(resumed&&!destroyed)refresh(true);});
    }
    private void cancelBookLookups(){bookCheckEpoch++;ShortnameResolver.cancelActive();}
    @Override protected void onStop(){privacyEpoch++;cancelBookLookups();
        if(networkWatcher!=null){android.net.ConnectivityManager manager=getSystemService(android.net.ConnectivityManager.class);if(manager!=null)manager.unregisterNetworkCallback(networkWatcher);networkWatcher=null;}
        cancelBiometricFlow(true);OfflineSnapshotStore.lock();clearSecrets(body);for(AlertDialog d:new ArrayList<>(sensitiveDialogs))d.dismiss();main.postDelayed(autoLock,30000);super.onStop();}
    @Override protected void onResume(){super.onResume();resumed=true;
        if(snapshot!=null&&snapshot.cached&&!repo.offlineSessionAuthorized()){
            snapshot.cached=false;snapshot.locked=true;snapshot.spendable=snapshot.pending=0;
            snapshot.address="";snapshot.history=new JSONArray();snapshot.sends=new JSONArray();render();
        }
        main.removeCallbacks(poll);main.postDelayed(poll,5000);refresh(false);checkUpdates(false);
    }
    @Override protected void onPause(){super.onPause();resumed=false;main.removeCallbacks(poll);}
    @Override protected void onDestroy(){cancelBiometricFlow(true);if(!isChangingConfigurations())worker.execute(()->{try{repo.lock();}catch(Exception ignored){}});destroyed=true;main.removeCallbacksAndMessages(null);worker.shutdown();updateWorker.shutdown();bookWorker.shutdownNow();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putString("page",page);state.putString("importName",pendingImportName);state.putString("payment",paymentText);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);paymentText=intent.getDataString();if(snapshot!=null&&snapshot.loaded&&!snapshot.locked)showSend();}
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){if(!page.equals("overview")){page="overview";render();}else super.onBackPressed();}

    private <T> void task(String label,Work<T> work,Done<T> done){
        if(busy)return;busy=true;if(loading!=null)loading.setVisibility(View.VISIBLE);setEnabledTree(body,false);
        worker.execute(()->{T value=null;Exception failure=null;try{value=work.run();}catch(Exception e){failure=e;}final T result=value;final Exception error=failure;
            main.post(()->{if(destroyed)return;busy=false;if(loading!=null)loading.setVisibility(View.GONE);setEnabledTree(body,true);updateStatus();
                if(error!=null){if(label.equals("Recovery phrase")&&error instanceof WalletApi.ApiException&&((WalletApi.ApiException)error).status==404)noRecoveryPhrase();else showError(label,error);refresh(true);}else try{done.accept(result);}catch(Exception e){showError(label,e);}
            });});
    }
    private void setEnabledTree(View view,boolean enabled){if(view==null)return;view.setEnabled(enabled);if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)setEnabledTree(g.getChildAt(i),enabled);}}
    private void refresh(boolean redraw){
        if(busy)return;
        if(refreshInFlight){if(redraw)refreshPending=true;return;}
        refreshInFlight=true;
        worker.execute(()->{try{WalletRepository.Snapshot next=repo.refresh();main.post(()->{refreshInFlight=false;if(destroyed)return;boolean state=needsRender(snapshot,next);boolean activityChanged=acceptSnapshot(next);if(redraw||state||(activityChanged&&(page.equals("history")||page.equals("overview"))))render();else updateStatus();if(resumed&&paymentText!=null&&snapshot.loaded&&!snapshot.locked&&!busy)showSend();runPendingRefresh();});}catch(Exception e){main.post(()->{refreshInFlight=false;if(!destroyed&&errorText!=null){errorText.setText("Node status: "+(e.getMessage()==null?"Waiting for local node":e.getMessage()));errorText.setVisibility(View.VISIBLE);}runPendingRefresh();});}});
    }
    private void runPendingRefresh(){if(refreshPending&&!destroyed){refreshPending=false;refresh(true);}}
    static boolean needsRender(WalletRepository.Snapshot previous,WalletRepository.Snapshot next){
        if(previous==null)return true;
        boolean wasReady=previous.status!=null&&previous.status.length()>0,ready=next.status!=null&&next.status.length()>0;
        return previous.running!=next.running||previous.loaded!=next.loaded||previous.locked!=next.locked||previous.cached!=next.cached||previous.cacheAvailable!=next.cacheAvailable||previous.syncRequested!=next.syncRequested||previous.syncAllowed!=next.syncAllowed||wasReady!=ready;
    }
    static boolean pageIdentityChanged(JSONArray previous,JSONArray next){
        if(previous==null||next==null)return previous!=next;
        if(previous.length()!=next.length())return true;
        for(int i=0;i<next.length();i++){JSONObject a=previous.optJSONObject(i),b=next.optJSONObject(i);if(a==null||b==null){if(a!=b)return true;continue;}
            if(!a.optString("txid").equals(b.optString("txid"))||a.optInt("output_index",-1)!=b.optInt("output_index",-1))return true;}
        return false;
    }
    static boolean pageContentChanged(JSONArray previous,JSONArray next){return previous==null?next!=null:next==null||!previous.toString().equals(next.toString());}
    private boolean acceptSnapshot(WalletRepository.Snapshot next){
        WalletRepository.Snapshot previous=snapshot;
        boolean changed=snapshot==null||pageContentChanged(snapshot.history,next.history)||pageContentChanged(snapshot.sends,next.sends);
        boolean outputs=snapshot==null||pageIdentityChanged(snapshot.history,next.history),sends=snapshot==null||pageIdentityChanged(snapshot.sends,next.sends);
        if(outputs){olderOutputs=new JSONArray();outputOffset=100;moreOutputs=next.history!=null&&next.history.length()>=100;}
        if(sends){olderSends=new JSONArray();sendOffset=100;moreSends=next.sends!=null&&next.sends.length()>=100;}
        snapshot=next;
        if(resumed&&next.loaded&&!next.locked&&!next.cached&&next.syncAllowed&&
            (previous==null||!previous.loaded||previous.locked||previous.cached))scheduleBookChecks();
        return changed;
    }
    private void scheduleBookChecks(){
        if(bookCheckRunning)return;
        bookCheckRunning=true;
        final int epoch=bookCheckEpoch,lookupEpoch=ShortnameResolver.cancellationEpoch();final String network=repo.network();
        bookWorker.execute(()->{
            boolean changed=false;
            try{
                if(epoch!=bookCheckEpoch||SyncNetworkPolicy.allowedNetwork(this)==null)return;
                long now=System.currentTimeMillis();
                android.content.SharedPreferences prefs=getSharedPreferences("address-book-checks",MODE_PRIVATE);
                List<AddressBook.Entry> due=addressBook.due(network,now,AddressBook.AUTO_INTERVAL,2);
                if(due.isEmpty()||epoch!=bookCheckEpoch||!AddressBookCheckBudget.reserve(AddressBookCheckBudget.preferences(prefs),network,now))return;
                for(AddressBook.Entry entry:due){
                    if(epoch!=bookCheckEpoch||!network.equals(repo.network())||SyncNetworkPolicy.allowedNetwork(this)==null)break;
                    try{AddressBook.Check checked=addressBook.recheck(entry.id,network,name->{
                            if(epoch!=bookCheckEpoch)throw new IOException("Address book check cancelled");
                            String resolved;
                            try{resolved=lookupBookName(name,network,lookupEpoch);}
                            catch(IOException failure){if(epoch!=bookCheckEpoch)throw new IOException("Address book check cancelled");throw failure;}
                            if(epoch!=bookCheckEpoch)throw new IOException("Address book check cancelled");
                            return resolved;
                        },System.currentTimeMillis());
                        if(checked.status==AddressBook.Status.ERROR)break;
                        changed=true;
                    }catch(IOException ignored){break;}
                }
            }catch(Exception ignored){}
            finally{final boolean updated=changed;main.post(()->{bookCheckRunning=false;if(updated&&!destroyed&&resumed&&page.equals("addressbook"))render();});}
        });
    }
    private void mutate(String label,Work<?> work){task(label,()->{work.run();return repo.refresh();},next->{acceptSnapshot(next);render();});}
    private void showError(String label,Exception error){String message=error.getMessage();if(message==null||message.trim().isEmpty())message="Operation could not be completed. Please try again.";if(errorText!=null){errorText.setText(LocalizedText.get(this,label)+": "+LocalizedText.get(this,message));errorText.setVisibility(View.VISIBLE);}new LocalizedText.Dialog(this).setTitle(label).setMessage(message).setPositiveButton("OK",null).show();}
    private void route(String destination){if(busy)return;page=destination;render();}
    private void lockWallet(){cancelBookLookups();mutate("Lock wallet",()->{repo.lock();return null;});}

    private void render(){
        generation++;rendering=true;balanceText=pendingText=chainText=null;sendButton=null;receiveQr=null;
        if(!page.equals("receive")||snapshot==null||!snapshot.loaded||snapshot.locked||snapshot.cached){receiveEntryKey="";receiveVerificationToken++;shortnameChecking=false;}
        if(snapshot!=null&&snapshot.locked)for(AlertDialog d:new ArrayList<>(openDialogs))d.dismiss();
        String key=repo.network()+"/"+repo.activeWallet();if(!key.equals(historyWalletKey)){historyWalletKey=key;olderOutputs=new JSONArray();olderSends=new JSONArray();outputOffset=sendOffset=100;moreOutputs=snapshot!=null&&snapshot.history!=null&&snapshot.history.length()>=100;moreSends=snapshot!=null&&snapshot.sends!=null&&snapshot.sends.length()>=100;}
        shell=ui.column();shell.setBackgroundColor(WalletTheme.BG);
        shell.setPadding(ui.dp(20),ui.dp(12),ui.dp(20),0);
        // Insets are supplied by the operating system for gesture navigation and display cutouts.
        shell.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets system=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(ui.dp(20)+system.left,ui.dp(8)+system.top,ui.dp(20)+system.right,system.bottom);}else{v.setPadding(ui.dp(20)+insets.getSystemWindowInsetLeft(),ui.dp(8)+insets.getSystemWindowInsetTop(),ui.dp(20)+insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());}return insets;});
        LinearLayout header=ui.row();TextView brand=ui.text("$ blocknet",21,WalletTheme.LIME);brand.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));nodeBadge=ui.text("connecting",11,WalletTheme.MUTED);header.addView(nodeBadge);shell.addView(header);
        loading=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);loading.setIndeterminate(true);loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(WalletTheme.LIME));loading.setVisibility(busy?View.VISIBLE:View.INVISIBLE);shell.addView(loading,new LinearLayout.LayoutParams(-1,ui.dp(3)));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);body=ui.column();body.setPadding(0,ui.dp(12),0,ui.dp(22));scroll.addView(body);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        errorText=ui.text("",12,WalletTheme.ERROR);errorText.setVisibility(View.GONE);body.addView(errorText);
        if(snapshot==null||snapshot.locked||!snapshot.loaded)body.addView(ui.button("Language / 语言",false,this::chooseLanguage));
        if(snapshot==null){ui.section(body,"starting your wallet");body.addView(ui.text("Connecting to the private node runtime…",14,WalletTheme.MUTED));}
        else switch(WalletEntryRoute.decide(snapshot.running,snapshot.syncAllowed,snapshot.status!=null&&snapshot.status.length()>0,
            snapshot.loaded,snapshot.locked,snapshot.cached,snapshot.cacheAvailable,snapshot.localWalletAvailable)){
            case STOPPED: stopped();break;
            case STARTING: ui.section(body,"starting local node");body.addView(ui.text(snapshot.nodeMessage,14,WalletTheme.MUTED));body.addView(ui.text("Waiting for the bundled core to become ready…",13,WalletTheme.MUTED));body.addView(ui.button("Node diagnostics",false,this::nodeSettings));break;
            case ONBOARDING: onboarding();break;
            case UNLOCK: unlock();break;
            case WALLET: if(snapshot.cached)cachedBanner();walletPage();break;
        }
        if(snapshot!=null&&snapshot.loaded&&!snapshot.locked){nav=ui.row();String[] names={"overview","receive","history","settings"};for(String name:names){Button b=ui.button(name.equals("overview")?"wallet":name,false,()->route(name));b.setTextSize(10);b.setMinWidth(0);b.setMinimumWidth(0);b.setSingleLine(true);b.setPadding(ui.dp(3),ui.dp(8),ui.dp(3),ui.dp(8));b.setTextColor(page.equals(name)?WalletTheme.LIME:WalletTheme.MUTED);nav.addView(b,new LinearLayout.LayoutParams(0,ui.dp(56),1));}shell.addView(nav);}
        setContentView(shell);shell.requestApplyInsets();updateStatus();rendering=false;
        if(resumed&&paymentText!=null&&snapshot!=null&&snapshot.loaded&&!snapshot.locked){main.post(this::showSend);}
    }
    private void walletPage(){switch(page){case "receive":receive();break;case "history":history();break;case "settings":settings();break;case "addressbook":addressBookPage();break;default:overview();}}
    private void cachedBanner(){
        LinearLayout note=ui.card();note.addView(ui.text("LAST SAVED WALLET VIEW",14,WalletTheme.ERROR));
        String at=AppLanguage.formatDate(this,snapshot.savedAt,java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT);
        note.addView(ui.text("Saved "+at+". Balances, activity and chain height may have changed. Sync and payments are unavailable until the node reconnects and the wallet is unlocked there.",12,WalletTheme.MUTED));
        if(snapshot.running&&snapshot.syncAllowed&&snapshot.status.length()>0){
            note.addView(ui.button("Unlock node for current balance",false,()->passwordPrompt("Open "+repo.activeWallet(),p->mutate("Unlock wallet",()->{repo.unlock(p);return null;}))));
            addBiometricOpen(note,repo.activeWallet());
        }
        else if(snapshot.running&&snapshot.syncAllowed)note.addView(ui.text("The node is loading. Unlock it when it becomes ready.",12,WalletTheme.MUTED));
        body.addView(note);
    }
    private void updateStatus(){
        if(snapshot==null)return;
        nodeBadge.setText(snapshot.cached?"◌ saved view":!snapshot.running?(snapshot.syncRequested?"◌ sync paused":"○ offline"):snapshot.syncRequested&&!snapshot.syncAllowed?"◌ stopping":snapshot.status.length()==0?"◌ starting":snapshot.syncing?"◌ syncing":"● node online");nodeBadge.setTextColor(snapshot.running&&!snapshot.cached?WalletTheme.LIME:WalletTheme.MUTED);
        if(sendButton!=null)sendButton.setEnabled(!busy&&!snapshot.cached&&!snapshot.viewOnly&&!snapshot.scanning&&!snapshot.syncing&&snapshot.syncAllowed);
        if(balanceText!=null)balanceText.setText(snapshot.scanning?"scanning…":coins(snapshot.spendable));
        if(pendingText!=null)pendingText.setText(snapshot.scanning?"Discovering wallet outputs":"pending  "+coins(snapshot.pending)+" BNT");
        for(TextView text:new ArrayList<>(syncStatusViews))text.setText(syncProgressText());
        for(TextView text:new ArrayList<>(checkpointStatusViews)){text.setText(checkpointStatusText());text.setTextColor((snapshot.checkpointWarning==null||snapshot.checkpointWarning.isEmpty())?WalletTheme.MUTED:WalletTheme.ERROR);}
        for(TextView text:new ArrayList<>(networkPolicyViews))text.setText(networkPolicyText());
        updatingPolicyControls=true;
        for(Switch control:new ArrayList<>(mobileDataSwitches)){control.setChecked(SyncNetworkPolicy.allowMobileData(this));control.setEnabled(!busy);}
        updatingPolicyControls=false;
        if(page.equals("receive")&&receiveQr!=null)updateReceiveRecipient();
        for(Switch control:new ArrayList<>(alertEnabledControls))control.setEnabled(!busy);
        for(Switch control:new ArrayList<>(alertSoundControls))control.setEnabled(!busy&&TransactionNotifier.enabled(this));
        for(Button button:new ArrayList<>(syncRunButtons)){button.setText(syncRequested()?"Stop sync":"Start wallet node");button.setEnabled(!busy);}
        if(chainText!=null){String line=(snapshot.cached&&!snapshot.chainHeightKnown?"chain height unavailable  /  wallet last scanned ":(snapshot.cached?"last saved chain ":"chain ")+snapshot.height+"  /  wallet ")+snapshot.walletHeight+"\n"+(snapshot.cached?"offline · ":snapshot.peers+" peers · ")+snapshot.network;chainText.setText(LocalizedText.get(this,line)+(snapshot.syncing?LocalizedText.get(this," · syncing"):""));}
    }
    private String syncProgressText(){
        if(snapshot==null)return "Waiting for node status…";
        if(snapshot.cached)return LocalizedText.get(this,snapshot.chainHeightKnown?"Last saved chain height: "+snapshot.height+" · no current sync status":
            "Wallet last scanned height: "+snapshot.walletHeight+" · chain height unavailable offline");
        String progress="Chain: "+snapshot.height;
        if(snapshot.syncTarget>0)progress+=" / "+snapshot.syncTarget+" blocks";
        else progress+=" blocks · finding network target";
        progress=LocalizedText.get(this,progress);
        if(snapshot.syncPercent!=null&&!snapshot.syncPercent.trim().isEmpty()){String percent=snapshot.syncPercent.trim();progress+=" · "+percent+(percent.endsWith("%")?"":"%");}
        return progress+(snapshot.syncing?LocalizedText.get(this,"\nSyncing with the network…"):"");
    }
    private String checkpointStatusText(){
        if(snapshot==null)return "Waiting for checkpoint status…";
        if(snapshot.cached)return "Checkpoint status unavailable in the saved view";
        String text=LocalizedText.get(this,snapshot.checkpointEntries>0?"Checkpoints loaded: "+snapshot.checkpointEntries+" · through block "+snapshot.checkpointHeight+"\nMode: "+LocalizedText.get(this,snapshot.checkpointFastSync?"checkpoint fast sync":"full validation"):"Checkpoints not loaded");
        if(snapshot.checkpointWarning!=null&&!snapshot.checkpointWarning.trim().isEmpty())text+="\n"+LocalizedText.get(this,snapshot.checkpointWarning);
        return text;
    }
    private void trackedStatus(LinearLayout target,boolean checkpoints){
        TextView text=ui.text(checkpoints?checkpointStatusText():syncProgressText(),12,WalletTheme.MUTED);
        Set<TextView> views=checkpoints?checkpointStatusViews:syncStatusViews;views.add(text);
        text.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){views.remove(text);}});
        target.addView(text);
    }
    private void nodeSyncStatus(LinearLayout target){
        trackedStatus(target,false);
        if((snapshot!=null?snapshot.network:repo.network()).equals("mainnet"))trackedStatus(target,true);
    }
    private boolean syncRequested(){return snapshot!=null&&(snapshot.syncRequested||snapshot.running);}
    private String networkPolicyText(){
        String mode=SyncNetworkPolicy.allowMobileData(this)?"Wi-Fi and mobile data":"Wi-Fi only";
        String connection=snapshot!=null&&snapshot.connectionLabel!=null?snapshot.connectionLabel:SyncNetworkPolicy.read(this).connectionLabel;
        String text=LocalizedText.get(this,"Sync network: "+LocalizedText.get(this,mode)+"\nConnection: "+LocalizedText.get(this,connection));
        if(snapshot!=null&&snapshot.syncRequested&&!snapshot.syncAllowed){String reason=snapshot.syncPolicyMessage;if(snapshot.running)text+="\n"+LocalizedText.get(this,"Stopping node for the sync network preference…");else text+="\n"+LocalizedText.get(this,reason==null||reason.trim().isEmpty()?"Waiting for a permitted network…":reason);}
        return text;
    }
    private void networkPolicyStatus(LinearLayout target){
        TextView text=ui.text(networkPolicyText(),12,WalletTheme.MUTED);networkPolicyViews.add(text);
        text.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){networkPolicyViews.remove(text);}});target.addView(text);
    }
    private void mobileDataSetting(LinearLayout target){
        Switch control=new Switch(this);control.setText(LocalizedText.get(this,"Allow mobile data for sync"));control.setTextColor(WalletTheme.TEXT);control.setTextSize(14);control.setTypeface(Typeface.MONOSPACE);control.setChecked(SyncNetworkPolicy.allowMobileData(this));control.setMinHeight(ui.dp(54));control.setPadding(0,ui.dp(8),0,ui.dp(8));control.setContentDescription(LocalizedText.get(this,"Allow mobile data for sync. Off means Wi-Fi only."));
        mobileDataSwitches.add(control);control.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){mobileDataSwitches.remove(control);}});
        control.setOnCheckedChangeListener((button,allowed)->{if(updatingPolicyControls)return;if(busy){updatingPolicyControls=true;control.setChecked(SyncNetworkPolicy.allowMobileData(this));updatingPolicyControls=false;return;}cancelBookLookups();for(Switch item:mobileDataSwitches)item.setEnabled(false);mutate("Sync network preference",()->{repo.setAllowMobileData(allowed);return null;});});
        target.addView(control);target.addView(ui.text("Off: Wi-Fi only. Turn on to sync over mobile data; carrier data charges may apply. Changing networks can pause or restart the node. Unlock your wallet after it restarts.",12,WalletTheme.MUTED));networkPolicyStatus(target);
    }
    private Button syncRunButton(Runnable before){
        Button button=ui.button(syncRequested()?"Stop sync":"Start wallet node",true,()->{before.run();if(syncRequested())mutate("Stop sync",()->{repo.stopNode();return null;});else mutate("Start sync",()->{repo.startNode();return null;});});
        syncRunButtons.add(button);button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){syncRunButtons.remove(button);}});return button;
    }
    private void stopped(){
        hero("Your money.\nYour keys.","A native wallet for zero-knowledge money.");
        LinearLayout c=ui.card();boolean waiting=snapshot.syncRequested&&!snapshot.syncAllowed;ui.section(c,waiting?"sync is paused":"node is stopped");
        if(snapshot.nodeMessage!=null&&!snapshot.nodeMessage.equals("Node stopped"))c.addView(ui.text(snapshot.nodeMessage,12,waiting?WalletTheme.MUTED:WalletTheme.ERROR));
        c.addView(ui.text(waiting?"Sync will resume when a permitted network is available. No saved wallet view is available yet; open this wallet once while online to prepare one.":"Start the bundled Blocknet core to open your wallet and connect to the network. No saved wallet view is available yet.",14,WalletTheme.MUTED));networkPolicyStatus(c);c.addView(syncRunButton(()->{}));body.addView(c);
        body.addView(ui.button("Node settings",false,this::nodeSettings));
        updateSection();
    }
    private void hero(String title,String sub){hero(title,sub,false);}
    private void hero(String title,String sub,boolean rawSubtitle){TextView titleView=ui.text(title,31,WalletTheme.TEXT);titleView.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);titleView.setPadding(0,ui.dp(24),0,ui.dp(10));body.addView(titleView);body.addView(rawSubtitle?ui.rawText(sub,13,WalletTheme.MUTED):ui.text(sub,13,WalletTheme.MUTED));}
    private void onboarding(){
        hero("Private by\ndefault.","Keep control of your BNT with keys stored on this device.");
        LinearLayout sync=ui.card();ui.section(sync,"node sync");nodeSyncStatus(sync);networkPolicyStatus(sync);body.addView(sync);
        ui.section(body,"get started");body.addView(ui.button("Create a wallet  →",true,()->walletForm("create")));
        body.addView(ui.button("Restore recovery phrase",false,()->walletForm("restore")));
        body.addView(ui.button("Import encrypted wallet file",false,this::importWallet));
        body.addView(ui.button("Import private keys / view-only",false,()->walletForm("keys")));
        body.addView(ui.button("Open an existing wallet",false,this::chooseWallet));addBiometricOpen(body,repo.activeWallet());
        body.addView(ui.button("Node settings",false,this::nodeSettings));
        updateSection();
    }
    private void unlock(){
        boolean saved=(!snapshot.running||!snapshot.syncAllowed||snapshot.status.length()==0)&&snapshot.localWalletAvailable;
        hero("Welcome back.",repo.activeWallet(),true);ui.section(body,saved?"unlock saved wallet":"unlock wallet");
        if(saved)body.addView(ui.text(snapshot.cacheAvailable?
            "Use your wallet password to view the last saved balance and activity. This view cannot send payments or show current network state.":
            "Use your wallet password to open the last state in the encrypted wallet file. Balances and activity may have changed; payments require reconnecting and unlocking the node.",13,WalletTheme.MUTED));
        EditText pass=ui.field("Wallet password",true,false);body.addView(pass);body.addView(ui.button(saved?"View saved wallet":"Unlock",true,()->{String p=pass.getText().toString();pass.setText("");mutate("Unlock",()->{repo.unlock(p);return null;});}));addBiometricOpen(body,repo.activeWallet());body.addView(ui.button("Switch wallet",false,this::chooseWallet));body.addView(ui.button("Node settings",false,this::nodeSettings));
    }
    private void overview(){
        SoftwareUpdateChecker.Release available=new SoftwareUpdateChecker(this).saved();
        if(available!=null){LinearLayout notice=ui.card();notice.addView(ui.text((available.signedAnnouncement?"Signed on-chain release notice: ":"Unverified GitHub preview listing: ")+available.tag,13,WalletTheme.LIME));notice.addView(ui.button("View update",false,()->route("settings")));body.addView(notice);}
        ui.section(body,"your wallet");LinearLayout balance=ui.card();balance.addView(ui.text(snapshot.cached?"LAST SAVED BALANCE":snapshot.viewOnly?"VIEW-ONLY · AVAILABLE":"AVAILABLE BALANCE",11,WalletTheme.MUTED));balanceText=ui.text(coins(snapshot.spendable),36,WalletTheme.TEXT);balanceText.setSingleLine(true);balanceText.setAutoSizeTextTypeUniformWithConfiguration(16,36,1,android.util.TypedValue.COMPLEX_UNIT_SP);balance.addView(balanceText);balance.addView(ui.text("BNT",15,WalletTheme.LIME));pendingText=ui.text("",12,WalletTheme.MUTED);balance.addView(pendingText);
        LinearLayout actions=ui.row();Button send=ui.button("↗ Send",true,this::showSend);sendButton=send;send.setEnabled(!snapshot.viewOnly&&!snapshot.scanning&&!snapshot.syncing);actions.addView(send,new LinearLayout.LayoutParams(0,-2,1));View gap=new View(this);actions.addView(gap,new LinearLayout.LayoutParams(ui.dp(12),1));actions.addView(ui.button("↙ Receive",false,()->route("receive")),new LinearLayout.LayoutParams(0,-2,1));balance.addView(actions);body.addView(balance);
        body.addView(ui.button("Address book",false,()->route("addressbook")));
        if(!snapshot.cached&&!repo.isBackupComplete()&&!snapshot.viewOnly){LinearLayout warning=ui.card();warning.addView(ui.text("# protect your wallet",14,WalletTheme.ERROR));warning.addView(ui.text("Verify recovery before changing phones or replacing this app. Imported-key wallets have no phrase; keep the original keys or an encrypted backup and its password.",13,WalletTheme.MUTED));warning.addView(ui.button("Check recovery phrase",false,this::revealSeed));warning.addView(ui.button("Export encrypted backup",false,this::exportWallet));body.addView(warning);}
        if(repo.hasPendingSend()){LinearLayout pending=ui.card();pending.addView(ui.text("A send is awaiting confirmation",14,WalletTheme.ERROR));if(!snapshot.cached)pending.addView(ui.button("Check transaction status",false,this::checkPending));else pending.addView(ui.text("Reconnect before checking its outcome or sending again.",12,WalletTheme.MUTED));body.addView(pending);}
        LinearLayout sync=ui.card();sync.addView(ui.text("# network",14,WalletTheme.LIME));chainText=ui.text("",12,WalletTheme.MUTED);sync.addView(chainText);nodeSyncStatus(sync);networkPolicyStatus(sync);if(snapshot.syncing)sync.addView(ui.text("Balances update as the node and wallet catch up. Keep the node running while syncing.",12,WalletTheme.MUTED));body.addView(sync);
        ui.section(body,"recent activity");String selectedFilter=historyFilter;historyFilter="all";addHistory(body,5);historyFilter=selectedFilter;body.addView(ui.button("View all activity",false,()->route("history")));body.addView(ui.button("Lock wallet",false,this::lockWallet));
    }
    private void addBiometricOpen(LinearLayout target,String file){
        if(Build.VERSION.SDK_INT>=28&&biometricVault.isEnabled(repo.network(),file))target.addView(ui.button("Unlock with biometrics",false,()->beginBiometricUnlock(file)));
    }
    private void biometricSetting(LinearLayout target){
        String network=repo.network(),file=repo.activeWallet();boolean enabled=biometricVault.isEnabled(network,file);
        Switch toggle=new Switch(this);toggle.setText(LocalizedText.get(this,"Biometric unlock for this wallet"));toggle.setTextColor(WalletTheme.TEXT);toggle.setTextSize(14);toggle.setTypeface(Typeface.MONOSPACE);toggle.setMinHeight(ui.dp(54));toggle.setChecked(enabled);toggle.setEnabled(enabled||biometricVault.available());target.addView(toggle);
        target.addView(ui.text(biometricVault.available()?"Optional fingerprint or strong biometric unlock. Your password stays encrypted on this device. Recovery phrases still require your wallet password.":LocalizedText.get(this,biometricVault.availabilityMessage())+" "+LocalizedText.get(this,"Use your wallet password to unlock."),12,WalletTheme.MUTED));
        toggle.setOnCheckedChangeListener((button,on)->{if(updatingBiometricSwitch)return;updatingBiometricSwitch=true;toggle.setChecked(enabled);updatingBiometricSwitch=false;if(busy)return;if(on)beginBiometricEnrollment();else mutate("Disable biometric unlock",()->{biometricVault.disable(network,file);return null;});});
    }
    private boolean biometricScopeCurrent(int session,int epoch,String network,String file,boolean requireActive){return !destroyed&&resumed&&session==biometricSession&&epoch==privacyEpoch&&network.equals(repo.network())&&(!requireActive||file.equals(repo.activeWallet()));}
    private void setBiometricBusy(boolean value){biometricBusy=value;busy=value;if(loading!=null)loading.setVisibility(value?View.VISIBLE:View.INVISIBLE);setEnabledTree(body,!value);updateStatus();}
    private void wipeBiometricPassword(){if(pendingBiometricPassword!=null){Arrays.fill(pendingBiometricPassword,'\0');pendingBiometricPassword=null;}}
    private void discardEnrollment(BiometricVault.Enrollment enrollment){if(enrollment==null)return;Runnable cleanup=()->{try{biometricVault.cancelEnrollment(enrollment);}catch(Exception ignored){}};try{worker.execute(cleanup);}catch(RejectedExecutionException stopped){new Thread(cleanup,"wallet-biometric-cleanup").start();}}
    private void discardBiometricCipher(javax.crypto.Cipher cipher){if(cipher==null)return;Runnable cleanup=()->biometricVault.cancelUnlock(cipher);try{worker.execute(cleanup);}catch(RejectedExecutionException stopped){new Thread(cleanup,"wallet-biometric-cipher-cleanup").start();}}
    private void cancelBiometricFlow(boolean lock){
        biometricSession++;javax.crypto.Cipher preparedCipher=pendingBiometricCipher;pendingBiometricCipher=null;discardBiometricCipher(preparedCipher);CancellationSignal signal=biometricCancellation;biometricCancellation=null;if(signal!=null)signal.cancel();
        BiometricVault.Enrollment enrollment=pendingBiometricEnrollment;pendingBiometricEnrollment=null;wipeBiometricPassword();discardEnrollment(enrollment);
        boolean wasBusy=biometricBusy;if(wasBusy){biometricBusy=false;busy=false;if(lock)worker.execute(()->{try{repo.lock();}catch(Exception ignored){}});}
    }
    private void finishBiometricFlow(int session){if(session!=biometricSession)return;biometricSession++;biometricCancellation=null;javax.crypto.Cipher preparedCipher=pendingBiometricCipher;pendingBiometricCipher=null;discardBiometricCipher(preparedCipher);BiometricVault.Enrollment enrollment=pendingBiometricEnrollment;pendingBiometricEnrollment=null;wipeBiometricPassword();discardEnrollment(enrollment);setBiometricBusy(false);}
    private void biometricFailure(int session,Exception error){if(session!=biometricSession)return;finishBiometricFlow(session);if(resumed){showError("Biometric unlock",error);refresh(true);}}
    private void beginBiometricEnrollment(){
        if(busy||!resumed)return;if(Build.VERSION.SDK_INT<28||!biometricVault.available()){showError("Biometric unlock",new Exception(biometricVault.availabilityMessage()));return;}
        final String network=repo.network(),file=repo.activeWallet();LinearLayout content=dialogBody();content.addView(ui.text("Verify your wallet password, then authenticate to enable biometric unlock for "+file+".",13,WalletTheme.MUTED));EditText password=ui.field("Wallet password",true,false);content.addView(password);
        AlertDialog dialog=dialog("Enable biometric unlock",content).setNegativeButton("Cancel",null).setPositiveButton("Continue",null).create();dialog.show();secure(dialog);
        dialog.getButton(-1).setOnClickListener(v->{int length=password.length();if(length==0){password.setError(LocalizedText.get(this,"Enter your wallet password"));return;}char[] secret=new char[length];password.getText().getChars(0,length,secret,0);password.setText("");dialog.dismiss();
            final int session=++biometricSession,epoch=privacyEpoch;pendingBiometricPassword=secret;setBiometricBusy(true);
            worker.execute(()->{BiometricVault.Enrollment enrollment=null;Exception failure=null;try{if(!biometricScopeCurrent(session,epoch,network,file,true))throw new CancellationException();repo.unlock(new String(secret));if(!biometricScopeCurrent(session,epoch,network,file,true))throw new CancellationException();enrollment=biometricVault.prepareEnrollment(network,file);}catch(Exception e){failure=e;}finally{if(!biometricScopeCurrent(session,epoch,network,file,true)){try{repo.lock();}catch(Exception ignored){}}}
                final BiometricVault.Enrollment prepared=enrollment;final Exception error=failure;main.post(()->{if(!biometricScopeCurrent(session,epoch,network,file,true)){discardEnrollment(prepared);if(session==biometricSession)finishBiometricFlow(session);return;}if(error!=null){biometricFailure(session,error);return;}pendingBiometricEnrollment=prepared;showBiometricPrompt(session,epoch,network,file,prepared.cipher,true);});
            });
        });
    }
    private void beginBiometricUnlock(String file){
        if(busy||!resumed)return;if(Build.VERSION.SDK_INT<28||!biometricVault.available()){showError("Biometric unlock",new Exception(biometricVault.availabilityMessage()));return;}
        final String network=repo.network();final int session=++biometricSession,epoch=privacyEpoch;setBiometricBusy(true);
        worker.execute(()->{javax.crypto.Cipher cipher=null;Exception failure=null;try{cipher=biometricVault.prepareUnlock(network,file);}catch(Exception e){failure=e;}final javax.crypto.Cipher prepared=cipher;final Exception error=failure;main.post(()->{if(!biometricScopeCurrent(session,epoch,network,file,false)){discardBiometricCipher(prepared);if(session==biometricSession)finishBiometricFlow(session);return;}if(error!=null){discardBiometricCipher(prepared);biometricFailure(session,error);return;}showBiometricPrompt(session,epoch,network,file,prepared,false);});});
    }
    @android.annotation.TargetApi(28)
    private void showBiometricPrompt(int session,int epoch,String network,String file,javax.crypto.Cipher cipher,boolean enrollment){
        if(!biometricScopeCurrent(session,epoch,network,file,enrollment)){discardBiometricCipher(cipher);finishBiometricFlow(session);return;}
        pendingBiometricCipher=cipher;try{biometricCancellation=new CancellationSignal();android.hardware.biometrics.BiometricPrompt.Builder builder=new android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle(LocalizedText.get(this,enrollment?"Enable biometric unlock":"Unlock Blocknet wallet")).setSubtitle(file).setDescription(LocalizedText.get(this,"Use a fingerprint or strong biometric. Your wallet password remains available.")).setNegativeButton(LocalizedText.get(this,"Use password"),getMainExecutor(),(d,w)->{boolean current=biometricScopeCurrent(session,epoch,network,file,enrollment);finishBiometricFlow(session);if(current&&!enrollment)passwordPrompt("Open "+file,p->mutate("Open wallet",()->{if(file.equals(repo.activeWallet()))repo.unlock(p);else repo.switchWallet(file,p);return null;}));});
        if(Build.VERSION.SDK_INT>=30)builder.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG);
        builder.build().authenticate(new android.hardware.biometrics.BiometricPrompt.CryptoObject(cipher),biometricCancellation,getMainExecutor(),new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
            @Override public void onAuthenticationError(int code,CharSequence message){if(session!=biometricSession)return;finishBiometricFlow(session);if(resumed&&code!=android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED&&code!=android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED)showError("Biometric unlock",new Exception(message.toString()+" "+LocalizedText.get(MainActivity.this,"Use your wallet password instead.")));}
            @Override public void onAuthenticationSucceeded(android.hardware.biometrics.BiometricPrompt.AuthenticationResult result){
                if(!biometricScopeCurrent(session,epoch,network,file,enrollment)){finishBiometricFlow(session);return;}
                javax.crypto.Cipher authenticated=result.getCryptoObject()==null?null:result.getCryptoObject().getCipher();if(authenticated==null){biometricFailure(session,new Exception("Biometric authentication did not authorize the wallet key. Use your password."));return;}
                biometricCancellation=null;final BiometricVault.Enrollment pending=pendingBiometricEnrollment;final char[] secret=pendingBiometricPassword;
                worker.execute(()->{WalletRepository.Snapshot next=null;Exception failure=null;try{if(!biometricScopeCurrent(session,epoch,network,file,enrollment))throw new CancellationException();if(enrollment){biometricVault.finishEnrollment(pending,authenticated,secret);if(!biometricScopeCurrent(session,epoch,network,file,true))biometricVault.disable(network,file);}else{char[] unlocked=biometricVault.decrypt(network,file,authenticated);try{if(!biometricScopeCurrent(session,epoch,network,file,false))throw new CancellationException();repo.unlockBiometric(network,file,unlocked);}finally{Arrays.fill(unlocked,'\0');}}if(!biometricScopeCurrent(session,epoch,network,file,enrollment)){repo.lock();throw new CancellationException();}next=repo.refresh();}catch(Exception e){failure=e;}finally{if(secret!=null)Arrays.fill(secret,'\0');if(!biometricScopeCurrent(session,epoch,network,file,enrollment)){try{repo.lock();}catch(Exception ignored){}}}
                    final WalletRepository.Snapshot state=next;final Exception error=failure;main.post(()->{if(session!=biometricSession)return;if(!biometricScopeCurrent(session,epoch,network,file,enrollment)){finishBiometricFlow(session);return;}if(error!=null){biometricFailure(session,error);return;}pendingBiometricEnrollment=null;finishBiometricFlow(session);acceptSnapshot(state);render();if(enrollment)Toast.makeText(MainActivity.this,LocalizedText.get(MainActivity.this,"Biometric unlock enabled for this wallet"),Toast.LENGTH_SHORT).show();});
                });
            }
        });
        }catch(Exception error){biometricFailure(session,error);}
    }
    void installMnemonicSuggestions(LinearLayout parent,EditText phrase){
        final MnemonicWords words;try{words=MnemonicWords.get(this);}catch(IOException missing){parent.addView(ui.text("Recovery-word suggestions are unavailable. Enter your 12 words manually.",12,WalletTheme.MUTED));return;}
        parent.addView(ui.text("Local BIP39 word suggestions appear as you type. Scroll sideways for all matches or type more letters to narrow them. Your completed phrase stays hidden.",11,WalletTheme.MUTED));
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(true);LinearLayout chips=ui.row();scroll.addView(chips,new HorizontalScrollView.LayoutParams(-2,-2));scroll.setVisibility(View.GONE);parent.addView(scroll,new LinearLayout.LayoutParams(-1,ui.dp(54)));
        Runnable refreshSuggestions=()->{
            chips.removeAllViews();if(!resumed||!phrase.hasFocus()){scroll.setVisibility(View.GONE);return;}
            android.text.Editable value=phrase.getText();int cursor=Math.min(value.length(),Math.max(0,phrase.getSelectionStart())),start=cursor;while(start>0&&!Character.isWhitespace(value.charAt(start-1)))start--;String prefix=value.subSequence(start,cursor).toString();List<String> matches=words.prefix(prefix,words.size());
            if(matches.isEmpty()){scroll.setVisibility(View.GONE);return;}scroll.setVisibility(View.VISIBLE);scroll.scrollTo(0,0);
            for(String word:matches){Button candidate=ui.rawButton(word,false,()->{android.text.Editable current=phrase.getText();int at=Math.max(0,phrase.getSelectionStart()),begin=at,end=at;while(begin>0&&!Character.isWhitespace(current.charAt(begin-1)))begin--;while(end<current.length()&&!Character.isWhitespace(current.charAt(end)))end++;if(end<current.length()&&Character.isWhitespace(current.charAt(end)))end++;phrase.requestFocus();current.replace(begin,end,word+" ");phrase.setSelection(begin+word.length()+1);chips.removeAllViews();scroll.setVisibility(View.GONE);});candidate.setTextSize(13);candidate.setContentDescription(LocalizedText.get(this,"Use recovery word "+word));LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-2,ui.dp(48));layout.rightMargin=ui.dp(7);chips.addView(candidate,layout);}
        };
        phrase.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(android.text.Editable value){refreshSuggestions.run();}});
        phrase.setOnFocusChangeListener((view,focused)->{if(focused)refreshSuggestions.run();else{chips.removeAllViews();scroll.setVisibility(View.GONE);}});
        int[] observedSelection={-1};android.view.ViewTreeObserver.OnPreDrawListener selectionWatcher=()->{int cursor=phrase.getSelectionStart();if(phrase.hasFocus()&&cursor!=observedSelection[0]){observedSelection[0]=cursor;refreshSuggestions.run();}return true;};
        phrase.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View view){phrase.getViewTreeObserver().addOnPreDrawListener(selectionWatcher);}public void onViewDetachedFromWindow(View view){if(phrase.getViewTreeObserver().isAlive())phrase.getViewTreeObserver().removeOnPreDrawListener(selectionWatcher);chips.removeAllViews();scroll.setVisibility(View.GONE);}});
    }
    private void walletForm(String mode){
        LinearLayout form=dialogBody();EditText name=ui.field("Wallet name",false,false);form.addView(name);
        EditText password=ui.field("Password (at least 12 characters)",true,false),repeat=ui.field("Repeat password",true,false);form.addView(password);form.addView(repeat);
        EditText seed=ui.field("12-word recovery phrase",true,true),spend=ui.field("Private spend key (hex)",true,true),view=ui.field("Private view key (hex)",true,true);CheckBox viewOnly=new CheckBox(this);viewOnly.setText(LocalizedText.get(this,"View-only wallet (cannot spend)"));viewOnly.setTextColor(WalletTheme.TEXT);
        if(mode.equals("restore")){form.addView(seed);installMnemonicSuggestions(form,seed);}if(mode.equals("keys")){form.addView(viewOnly);form.addView(spend);form.addView(view);viewOnly.setOnCheckedChangeListener((b,checked)->{spend.setHint(LocalizedText.get(this,checked?"Public spend key (hex)":"Private spend key (hex)"));spend.setContentDescription(LocalizedText.get(this,checked?"Public spend key (hex)":"Private spend key (hex)"));});}
        TextView validation=ui.text("",12,WalletTheme.ERROR);form.addView(validation);
        AlertDialog d=dialog(mode.equals("create")?"Create wallet":mode.equals("restore")?"Restore wallet":"Import keys",form).setPositiveButton("Continue",null).setNegativeButton("Cancel",null).create();d.show();secure(d);
        d.getButton(-1).setOnClickListener(v->{String n=name.getText().toString().trim(),p=password.getText().toString();if(n.isEmpty()){validation.setText("Enter a wallet name.");return;}if(p.length()<12){validation.setText("Use at least 12 characters for your password.");return;}if(!p.equals(repeat.getText().toString())){validation.setText("Passwords do not match.");return;}
            String phrase=seed.getText().toString().trim(),s=spend.getText().toString().trim(),k=view.getText().toString().trim();boolean watch=viewOnly.isChecked();
            if(mode.equals("restore")&&phrase.split("\\s+").length!=12){validation.setText("Enter all 12 recovery words in order.");return;}
            if(mode.equals("keys")&&(!k.matches("[0-9a-fA-F]{64}")||!s.matches("[0-9a-fA-F]{64}"))){validation.setText("Keys must contain 64 hexadecimal characters.");return;}
            password.setText("");repeat.setText("");seed.setText("");spend.setText("");view.setText("");d.dismiss();
            int epoch=privacyEpoch;task("Set up wallet",()->{if(mode.equals("create"))repo.create(n,p);else if(mode.equals("restore"))repo.restore(n,p,phrase);else repo.importKeys(n,p,s,k,watch);return repo.refresh();},next->{acceptSnapshot(next);page="overview";render();if(mode.equals("create")&&resumed&&epoch==privacyEpoch)task("Recovery phrase",()->repo.seed(p),phraseResult->{if(epoch==privacyEpoch)showSeed(phraseResult);});});
        });
    }
    private void chooseWallet(){task("Wallet list",repo::wallets,list->{LinearLayout options=dialogBody();if(list.isEmpty())options.addView(ui.text("No wallet files found on this network.",14,WalletTheme.MUTED));AlertDialog d=dialog("Choose wallet",options).setNegativeButton("Cancel",null).create();for(String file:list){options.addView(ui.rawButton(file,false,()->{d.dismiss();passwordPrompt("Open "+file,p->mutate("Open wallet",()->{repo.switchWallet(file,p);return null;}));}));if(biometricVault.isEnabled(repo.network(),file))options.addView(ui.button("Unlock "+file+" with biometrics",false,()->{d.dismiss();beginBiometricUnlock(file);}));}options.addView(ui.button("Create / restore another wallet",false,()->{d.dismiss();new LocalizedText.Dialog(this).setTitle("Add wallet").setItems(new String[]{"Create new","Restore phrase","Import keys","Import file"},(x,n)->{if(n==3)importWallet();else walletForm(n==0?"create":n==1?"restore":"keys");}).show();}));d.show();secure(d);});}
    interface PasswordAction{void accept(String password);}
    private void passwordPrompt(String title,PasswordAction action){LinearLayout content=dialogBody();EditText pass=ui.field("Wallet password",true,false);content.addView(pass);AlertDialog d=dialog(title,content).setPositiveButton("Continue",null).setNegativeButton("Cancel",null).create();d.show();secure(d);d.getButton(-1).setOnClickListener(v->{String password=pass.getText().toString();if(password.isEmpty()){pass.setError(LocalizedText.get(this,"Enter password"));return;}pass.setText("");d.dismiss();action.accept(password);});}
    private void revealSeed(){if(snapshot==null||snapshot.viewOnly)return;passwordPrompt("Reveal recovery phrase",p->{int epoch=privacyEpoch;task("Recovery phrase",()->{try{return repo.seed(p);}catch(WalletApi.ApiException missing){if(missing.status==404)return null;throw missing;}},phrase->{if(epoch==privacyEpoch){if(phrase==null)noRecoveryPhrase();else showSeed(phrase);}});});}
    private void noRecoveryPhrase(){if(!resumed)return;new LocalizedText.Dialog(this).setTitle("No recovery phrase").setMessage("This wallet was imported from keys and has no recovery phrase. Keep an encrypted wallet backup AND its password, or the original spend and view keys, in a safe place. Verify restoration before replacing or removing this app.").setNegativeButton("Close",null).setPositiveButton("Export backup",(d,w)->exportWallet()).show();}
    private void showSeed(String phrase){
        if(!resumed||snapshot==null||snapshot.locked)return;
        LinearLayout content=dialogBody();content.addView(ui.text("Write these words on paper, in order. Anyone with this phrase can spend your funds.",13,WalletTheme.ERROR));String[] words=phrase.trim().split("\\s+");for(int i=0;i<words.length;i++)content.addView(ui.rawText(String.format(Locale.US,"%02d  %s",i+1,words[i]),17,WalletTheme.TEXT));content.addView(ui.text("Keep your backup offline and private.",12,WalletTheme.MUTED));
        AlertDialog d=dialog("Recovery phrase",content).setNegativeButton("Close",null).setPositiveButton("I wrote it down",null).create();d.show();secure(d);d.getButton(-1).setOnClickListener(v->{d.dismiss();LinearLayout verify=dialogBody();verify.addView(ui.text("Enter recovery word #"+words.length+" to confirm your backup.",14,WalletTheme.MUTED));EditText word=ui.field("Last recovery word",true,false);verify.addView(word);AlertDialog confirm=dialog("Confirm backup",verify).setNegativeButton("Cancel",null).setPositiveButton("Confirm",null).create();confirm.show();secure(confirm);confirm.getButton(-1).setOnClickListener(b->{if(!word.getText().toString().trim().equals(words[words.length-1])){word.setError(LocalizedText.get(this,"Word does not match"));return;}word.setText("");repo.markBackupComplete();Arrays.fill(words,"");confirm.dismiss();render();});});
    }
    private String bookName(AddressBook.Entry entry){return !entry.label.isEmpty()?entry.label:!entry.handle.isEmpty()?"$"+entry.handle:shorten(entry.address);}
    private String bookStatus(AddressBook.Entry entry){
        if(entry.handle.isEmpty())return "Full address saved; no shortname known.";
        String at=entry.checkedAt>0?AppLanguage.formatDate(this,entry.checkedAt,java.text.DateFormat.MEDIUM,java.text.DateFormat.MEDIUM):LocalizedText.get(this,"never");
        String status;
        switch(entry.status){
            case MATCH:status="$"+entry.handle+" matched this address when checked "+at+". Recheck before sending.";break;
            case CHANGED:status="$"+entry.handle+" pointed to a different address when checked "+at+". Saved address unchanged.";break;
            case MISSING:status="$"+entry.handle+" was not found when checked "+at+". Saved address unchanged.";break;
            default:status="$"+entry.handle+" has not been verified against this saved address.";
        }
        status=LocalizedText.get(this,status);
        if(entry.attemptedAt>entry.checkedAt)status+=" "+LocalizedText.get(this,"A later check was attempted but did not confirm a mapping.");
        return status;
    }
    private boolean bookOnline(){return snapshot!=null&&!snapshot.cached&&snapshot.syncAllowed&&snapshot.running;}
    private void addressBookPage(){
        hero("Address book.","Saved recipients stay on this device and are separate for mainnet and testnet.");
        body.addView(ui.text("A saved full address never changes when its shortname changes. Last checked names come from the Blocknet ID HTTPS service; they are not independently authenticated. Names are checked before using one to send. Automatic checks cover at most two stale names per network per day after a live unlock over a permitted connection.",13,WalletTheme.MUTED));
        if(!bookOnline())body.addView(ui.text("Offline: saved names and check times may be stale. Reconnect to add, edit, or verify recipients; sending is unavailable.",12,WalletTheme.ERROR));
        Button add=ui.button("Add recipient",false,()->editBookEntry(null));add.setEnabled(bookOnline());body.addView(add);
        List<AddressBook.Entry> entries;
        try{entries=addressBook.list(repo.network());}catch(IOException error){body.addView(ui.text("Address book could not be opened: "+error.getMessage(),13,WalletTheme.ERROR));return;}
        if(entries.isEmpty()){body.addView(ui.text("No saved recipients on this network yet.",13,WalletTheme.MUTED));return;}
        for(AddressBook.Entry entry:entries){
            LinearLayout card=ui.card();card.addView(ui.rawText(bookName(entry),17,WalletTheme.LIME));card.addView(selectable(entry.address,12));
            card.addView(ui.text(bookStatus(entry),12,entry.status==AddressBook.Status.CHANGED||entry.status==AddressBook.Status.MISSING?WalletTheme.ERROR:WalletTheme.MUTED));
            if(!entry.handle.isEmpty()){
                Button check=ui.button("Check shortname",false,()->checkBookEntry(entry));check.setEnabled(bookOnline());card.addView(check);
            }
            Button send=ui.button("Send to saved address",false,()->sendToBookEntry(entry));
            send.setEnabled(bookOnline()&&!snapshot.viewOnly&&!snapshot.scanning&&!snapshot.syncing);card.addView(send);
            Button edit=ui.button("Edit",false,()->editBookEntry(entry));edit.setEnabled(bookOnline());card.addView(edit);
            card.addView(ui.button("Delete",false,()->{
                LinearLayout content=dialogBody();content.addView(ui.text("Remove "+bookName(entry)+" from this device's address book?",13,WalletTheme.TEXT));
                AlertDialog confirm=dialog("Delete recipient",content).setNegativeButton("Cancel",null)
                    .setPositiveButton("Delete",(d,w)->task("Delete recipient",()->{addressBook.delete(entry.id,repo.network());return true;},ignored->render())).create();
                confirm.show();secure(confirm);
            }));
            body.addView(card);
        }
    }
    private String lookupBookName(String handle,String network,int lookupEpoch) throws IOException {
        try{
            if(!network.equals(repo.network()))throw new IOException("Wallet network changed. Check the recipient again.");
            String resolved=new ShortnameResolver(this).resolve("$"+handle,lookupEpoch).address;
            if(!network.equals(repo.network()))throw new IOException("Wallet network changed. Check the recipient again.");
            repo.validateRecipientAddress(resolved);
            if(!network.equals(repo.network()))throw new IOException("Wallet network changed. Check the recipient again.");
            return resolved;
        }
        catch(ShortnameResolver.NotFoundException absent){throw new AddressBook.NameNotFound();}
        catch(IOException failure){throw failure;}
        catch(Exception failure){throw new IOException("Core could not validate the resolved address",failure);}
    }
    private void checkBookEntry(AddressBook.Entry entry){
        if(!bookOnline())return;
        String network=repo.network();int lookupEpoch=ShortnameResolver.cancellationEpoch();
        task("Check shortname",()->addressBook.recheck(entry.id,network,name->lookupBookName(name,network,lookupEpoch),System.currentTimeMillis()),checked->{
            render();if(checked.status==AddressBook.Status.ERROR)showError("Shortname check",new IOException(checked.message));
        });
    }
    private void editBookEntry(AddressBook.Entry old){
        if(!bookOnline())return;
        LinearLayout fields=dialogBody();
        fields.addView(ui.text("Enter a full Blocknet address or shortname. A full address has no supported reverse lookup; enter a known shortname below if you want it checked.",12,WalletTheme.MUTED));
        EditText target=ui.field("Full address or $shortname / @handle",false,true);
        EditText name=ui.field("Optional $shortname for full address",false,false);
        EditText label=ui.field("Optional label",false,false);
        if(old!=null){target.setText(old.address);if(!old.handle.isEmpty())name.setText("$"+old.handle);label.setText(old.label);}
        fields.addView(target);fields.addView(name);fields.addView(label);
        AlertDialog dialog=dialog(old==null?"Add recipient":"Edit recipient",fields).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.show();secure(dialog);
        dialog.getButton(-1).setOnClickListener(v->{
            String entered=target.getText().toString().trim(),rawName=name.getText().toString().trim(),title=label.getText().toString();
            boolean byName=AddressBook.isNameInput(entered);
            if(byName&&!rawName.isEmpty()){target.setError(LocalizedText.get(this,"Use the shortname in one field only"));return;}
            String network=repo.network(),id=old==null?"":old.id;
            long expectedRevision=old==null?0:old.revision;
            int lookupEpoch=ShortnameResolver.cancellationEpoch();
            task("Save recipient",()->{
                String full,handle="";boolean verified=false;
                if(byName){
                    ShortnameResolver.Result result=new ShortnameResolver(this).resolve(entered,lookupEpoch);
                    full=AddressBook.address(result.address,network);handle=result.handle;verified=true;
                }else{
                    full=AddressBook.address(entered,network);
                    if(!rawName.isEmpty()){
                        handle=ShortnameResolver.normalize(rawName);
                        String resolved=AddressBook.address(lookupBookName(handle,network,lookupEpoch),network);
                        if(!full.equals(resolved))throw new IOException("That shortname currently points to another address. The saved address was not changed.");
                        verified=true;
                    }
                }
                repo.validateRecipientAddress(full);
                if(!network.equals(repo.network()))throw new IOException("Wallet network changed. Save the recipient again.");
                return addressBook.save(id,network,full,handle,title,verified,System.currentTimeMillis(),expectedRevision);
            },saved->{dialog.dismiss();render();});
        });
    }
    private void sendToBookEntry(AddressBook.Entry entry){
        if(!bookOnline()||snapshot.viewOnly||snapshot.scanning||snapshot.syncing)return;
        showSend();if(sendAddress!=null&&sendDialog!=null&&sendDialog.isShowing())selectBookRecipient(entry);
    }
    private void selectBookRecipient(AddressBook.Entry entry){
        sendAddress.setText(entry.address);selectedBookRecipient=entry.id;selectedBookAddress=entry.address;selectedBookRevision=entry.revision;
        if(selectedBookHint!=null)selectedBookHint.setText("Saved recipient: "+bookName(entry)+". "+bookStatus(entry));
    }
    private void chooseBookRecipient(){
        List<AddressBook.Entry> entries;
        try{entries=addressBook.list(repo.network());}catch(IOException error){showError("Address book",error);return;}
        if(entries.isEmpty()){new LocalizedText.Dialog(this).setTitle("Address book").setMessage("No saved recipients on this network yet.").setPositiveButton("OK",null).show();return;}
        AlertDialog[] picker={null};LinearLayout choices=dialogBody();for(AddressBook.Entry entry:entries)choices.addView(ui.rawButton(bookName(entry)+" · "+shorten(entry.address),false,()->{
            picker[0].dismiss();selectBookRecipient(entry);
        }));
        picker[0]=dialog("Select recipient",choices).setNegativeButton("Cancel",null).create();picker[0].show();secure(picker[0]);
    }
    private void requireSelectedBookCurrent(String id,long revision,String network,String address,String amount,String memo) throws IOException {
        if(!network.equals(repo.network())||!id.equals(selectedBookRecipient)||!address.equals(selectedBookAddress)
            ||sendAddress==null||!address.equals(sendAddress.getText().toString().trim())
            ||sendAmount==null||!amount.equals(sendAmount.getText().toString().trim())
            ||sendMemo==null||!memo.equals(sendMemo.getText().toString())
            ||!AddressBook.selectionCurrent(addressBook.get(id,network),revision,address,address))
            throw new IOException("Saved recipient or payment form changed. Choose the recipient and review payment again.");
    }
    private void showSend(){
        if(!resumed||snapshot==null||!snapshot.loaded||snapshot.locked||snapshot.cached||snapshot.viewOnly)return;
        if(sendDialog!=null&&sendDialog.isShowing()){if(paymentText!=null){applyPayment(paymentText);paymentText=null;}return;}
        selectedBookRecipient="";selectedBookAddress="";selectedBookRevision=0;
        LinearLayout form=dialogBody();form.addView(ui.text("Spendable: "+coins(snapshot.spendable)+" BNT",13,WalletTheme.MUTED));
        sendAddress=ui.field("Recipient address or $shortname / @handle",false,true);sendAmount=ui.field("Amount in BNT",false,false);sendAmount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);sendMemo=ui.field("Optional private memo",false,true);form.addView(sendAddress);form.addView(ui.button("Choose saved recipient",false,this::chooseBookRecipient));selectedBookHint=ui.text("",12,WalletTheme.MUTED);form.addView(selectedBookHint);form.addView(ui.button("Scan payment QR",false,()->startActivityForResult(new Intent(this,ScannerActivity.class),503)));form.addView(sendAmount);form.addView(sendMemo);
        sendAddress.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){}public void afterTextChanged(android.text.Editable s){if(!selectedBookRecipient.isEmpty()&&!selectedBookAddress.equals(s.toString().trim())){selectedBookRecipient="";selectedBookAddress="";selectedBookRevision=0;selectedBookHint.setText("");}}});
        if(paymentText!=null){applyPayment(paymentText);paymentText=null;}
        TextView error=ui.text("",12,WalletTheme.ERROR);form.addView(error);
        AlertDialog d=dialog("Send BNT",form).setNegativeButton("Cancel",null).setPositiveButton("Review payment",null).create();sendDialog=d;d.show();secure(d);
        d.getButton(-1).setOnClickListener(v->{String address=sendAddress.getText().toString().trim(),amount=sendAmount.getText().toString().trim(),memo=sendMemo.getText().toString();try{if(address.trim().isEmpty())throw new IllegalArgumentException("Enter a recipient.");BigDecimal a=new BigDecimal(amount);if(a.signum()<=0||a.scale()>8)throw new IllegalArgumentException("Use a positive amount with at most 8 decimals.");a.movePointRight(8).longValueExact();}catch(Exception e){error.setText(e.getMessage()==null?"Enter a valid amount.":e.getMessage());return;}
            String selectedId=selectedBookRecipient;
            try{
                if(!selectedId.isEmpty()){
                    AddressBook.Entry selected=addressBook.get(selectedId,repo.network());
                    if(!AddressBook.selectionCurrent(selected,selectedBookRevision,selectedBookAddress,address))
                        throw new IOException("Saved recipient changed after selection. Choose it again before reviewing payment.");
                    if(!selected.handle.isEmpty()){
                        int epoch=privacyEpoch,lookupEpoch=ShortnameResolver.cancellationEpoch();String network=repo.network();long revision=selectedBookRevision;
                        task("Check saved shortname",()->addressBook.recheck(selectedId,network,name->lookupBookName(name,network,lookupEpoch),System.currentTimeMillis()),checked->{
                            if(!resumed||epoch!=privacyEpoch||!d.isShowing())return;
                            try{requireSelectedBookCurrent(selectedId,revision,network,address,amount,memo);}
                            catch(IOException changed){showError("Address book",changed);return;}
                            if(checked.canReviewWithoutChoice())reviewPayment(d,address,amount,memo);
                            else confirmSavedAddressAfterNameChange(d,checked,address,amount,memo,selectedId,revision,network);
                        });return;
                    }
                }
            }catch(IOException missing){showError("Address book",missing);return;}
            reviewPayment(d,address,amount,memo);
        });
    }
    private void reviewPayment(AlertDialog dialog,String address,String amount,String memo){
        int epoch=privacyEpoch;String selectedId=selectedBookRecipient,network=repo.network();long revision=selectedBookRevision;
        task("Review payment",()->repo.previewSend(address,amount,memo),preview->{
            if(!resumed||epoch!=privacyEpoch||!dialog.isShowing())return;
            if(!selectedId.isEmpty())try{requireSelectedBookCurrent(selectedId,revision,network,address,amount,memo);}
            catch(IOException changed){showError("Address book",changed);return;}
            dialog.dismiss();confirmSend(preview,address,amount,memo,selectedId,revision,network);
        });
    }
    private void confirmSavedAddressAfterNameChange(AlertDialog send,AddressBook.Check checked,String address,String amount,String memo,String selectedId,long revision,String network){
        String detail;
        if(checked.status==AddressBook.Status.CHANGED)detail="$"+checked.entry.handle+" now resolves to "+checked.observedAddress+". The address book still holds "+address+".";
        else if(checked.status==AddressBook.Status.MISSING)detail="$"+checked.entry.handle+" is no longer found. The address book still holds "+address+".";
        else detail="The current $"+checked.entry.handle+" mapping could not be verified: "+LocalizedText.get(this,checked.message)+". The address book still holds "+address+".";
        LinearLayout content=dialogBody();content.addView(ui.text(LocalizedText.get(this,detail)+" "+LocalizedText.get(this,"Send only if you independently recognize the saved full address."),13,WalletTheme.ERROR));
        AlertDialog warning=dialog("Saved shortname needs attention",content)
            .setNegativeButton("Cancel",null).setPositiveButton("Use saved address",(d,w)->{
                if(send.isShowing())try{requireSelectedBookCurrent(selectedId,revision,network,address,amount,memo);reviewPayment(send,address,amount,memo);}
                catch(IOException changed){showError("Address book",changed);}
            }).create();warning.show();secure(warning);
    }
    private void confirmSend(JSONObject preview,String original,String amount,String memo,String selectedId,long revision,String network){
        JSONArray recipients=preview.optJSONArray("recipients");JSONObject recipient=recipients==null?null:recipients.optJSONObject(0);String resolved=recipient==null?original:recipient.optString("resolved_address",recipient.optString("address",original));
        LinearLayout summary=dialogBody();summary.addView(ui.text("Review every detail. Broadcast transactions cannot be reversed.",13,WalletTheme.ERROR));summary.addView(ui.text("TO",11,WalletTheme.MUTED));summary.addView(selectable(resolved,13));if(!resolved.equals(original))summary.addView(ui.text("Resolved from "+original,12,WalletTheme.MUTED));ui.line(summary,"Amount",amount+" BNT");ui.line(summary,"Estimated network fee",coins(preview.optLong("fee"))+" BNT");try{ui.line(summary,"Total",new BigDecimal(amount).add(BigDecimal.valueOf(preview.optLong("fee"),8)).stripTrailingZeros().toPlainString()+" BNT");}catch(Exception ignored){}if(!memo.trim().isEmpty())summary.addView(ui.text("Memo: "+memo,13,WalletTheme.TEXT));
        AlertDialog confirm=dialog("Confirm payment",summary).setNegativeButton("Cancel",null).setPositiveButton("Send "+amount+" BNT",null).create();confirm.show();secure(confirm);confirm.getButton(-1).setOnClickListener(v->{confirm.dismiss();task("Send payment",()->{
            if(!selectedId.isEmpty()&&(!network.equals(repo.network())||!AddressBook.selectionCurrent(addressBook.get(selectedId,network),revision,original,original)))
                throw new IOException("Saved recipient changed. Choose it and review payment again.");
            return repo.send(resolved,amount,memo);
        },result->{showJson("Payment submitted",result);refresh(true);});});
    }
    private void applyPayment(String text){
        try{PaymentRequest request=PaymentRequest.parse(text);selectedBookRecipient="";selectedBookAddress="";selectedBookRevision=0;if(selectedBookHint!=null)selectedBookHint.setText("");sendAddress.setText(request.address);if(request.amount!=null)sendAmount.setText(request.amount);if(request.memo!=null)sendMemo.setText(request.memo);}catch(Exception e){showError("Payment QR",e);}
    }
    private String receiveScopeKey(){return repo.network()+"/"+repo.activeWallet()+"/"+(snapshot==null?"":snapshot.address);}
    private String receiveRecipient(){String preferred=receiveForceAddress?"":repo.preferredReceiveShortname();return preferred.isEmpty()?(snapshot==null?"":snapshot.address):preferred;}
    private void updateReceiveRecipient(){
        if(receiveQr==null||snapshot==null)return;String recipient=receiveRecipient();boolean shortname=recipient.startsWith("$");
        if(!recipient.equals(renderedReceiveRecipient)){try{receiveQr.setImageBitmap(qr(recipient));renderedReceiveRecipient=recipient;}catch(Exception e){shortnameNotice="Receive QR could not be rendered. Copy the full wallet address instead.";}}
        receiveQr.setContentDescription(LocalizedText.get(this,shortname?"QR code containing verified Blocknet shortname "+recipient:"QR code containing your public Blocknet address"));
        if(receiveRecipientText!=null){receiveRecipientText.setText(shortname?recipient:"Your public wallet address");receiveRecipientText.setTextColor(shortname?WalletTheme.LIME:WalletTheme.MUTED);}
        if(receiveFullCopy!=null)receiveFullCopy.setVisibility(shortname?View.VISIBLE:View.GONE);if(receiveCopy!=null)receiveCopy.setText(shortname?"Copy shortname":"Copy address");if(receiveShare!=null)receiveShare.setText(shortname?"Share shortname":"Share address");
        if(shortnameStatus!=null){String saved=repo.savedReceiveShortname();String status=shortnameChecking?"Checking signed registry mapping…":!shortnameNotice.isEmpty()?shortnameNotice:shortname?"Verified: "+recipient+" resolves to this wallet.":saved.isEmpty()?"Optional. A shortname is used only after its signed registry mapping matches this wallet.":"Saved "+saved+" is not currently verified. Using the full address.";shortnameStatus.setText(status);}
        if(preferredShortnameField!=null)preferredShortnameField.setEnabled(!busy&&!shortnameChecking);if(shortnameSave!=null)shortnameSave.setEnabled(!busy&&!shortnameChecking);if(shortnameClear!=null)shortnameClear.setEnabled(!busy&&!shortnameChecking&&!repo.savedReceiveShortname().isEmpty());
    }
    private void verifyReceiveShortname(String value,String expectedScope){
        if(busy||shortnameChecking||!page.equals("receive")||!expectedScope.equals(receiveScopeKey()))return;
        final String network=repo.network(),file=repo.activeWallet();final int token=++receiveVerificationToken;shortnameChecking=true;receiveForceAddress=true;shortnameNotice="";updateReceiveRecipient();
        worker.execute(()->{String verified=null;Exception failure=null;try{if(!network.equals(repo.network())||!file.equals(repo.activeWallet()))throw new IOException("The wallet changed. Verify the shortname again.");verified=repo.verifyAndSaveReceiveShortname(value);}catch(Exception e){failure=e;}final String canonical=verified;final Exception error=failure;
            main.post(()->{if(destroyed||token!=receiveVerificationToken||!page.equals("receive")||!expectedScope.equals(receiveScopeKey()))return;shortnameChecking=false;if(error==null){receiveForceAddress=false;shortnameNotice="";if(preferredShortnameField!=null)preferredShortnameField.setText(canonical);}else shortnameNotice="Could not verify this shortname. Using the full wallet address. "+(error.getMessage()==null?"Try again when online.":error.getMessage());updateReceiveRecipient();});
        });
    }
    private void receive(){
        ui.section(body,"receive BNT");body.addView(ui.text("Share your wallet address or a verified shortname to receive private payments.",13,WalletTheme.MUTED));LinearLayout c=ui.card();String address=snapshot.address==null?"":snapshot.address;
        receiveQr=new ImageView(this);receiveQr.setBackground(ui.box(QrRenderer.BACKGROUND));receiveQr.setClipToOutline(true);receiveQr.setPadding(ui.dp(10),ui.dp(10),ui.dp(10),ui.dp(10));LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(ui.dp(250),ui.dp(250));size.gravity=Gravity.CENTER;size.bottomMargin=ui.dp(16);c.addView(receiveQr,size);renderedReceiveRecipient="";
        receiveRecipientText=ui.text("",16,WalletTheme.LIME);receiveRecipientText.setTextIsSelectable(true);c.addView(receiveRecipientText);c.addView(ui.text("Wallet address",11,WalletTheme.MUTED));c.addView(selectable(address,12));receiveCopy=ui.button("Copy address",true,()->copy("Blocknet receive details",receiveRecipient()));receiveShare=ui.button("Share address",false,()->share(receiveRecipient()));c.addView(receiveCopy);receiveFullCopy=ui.button("Copy full address",false,()->copy("Blocknet wallet address",snapshot.address));c.addView(receiveFullCopy);c.addView(receiveShare);body.addView(c);
        if(!snapshot.cached){LinearLayout preference=ui.card();ui.section(preference,"preferred shortname");preferredShortnameField=ui.field("Optional $shortname",false,false);preferredShortnameField.setText(repo.savedReceiveShortname());preference.addView(preferredShortnameField);shortnameStatus=ui.text("",12,WalletTheme.MUTED);preference.addView(shortnameStatus);shortnameSave=ui.button("Verify & save",false,()->verifyReceiveShortname(preferredShortnameField.getText().toString().trim(),receiveScopeKey()));shortnameClear=ui.button("Clear shortname",false,()->task("Clear shortname",()->{repo.clearReceiveShortname();return true;},cleared->{receiveVerificationToken++;shortnameChecking=false;receiveForceAddress=true;shortnameNotice="Shortname cleared. Using the full address.";preferredShortnameField.setText("");updateReceiveRecipient();}));preference.addView(shortnameSave);preference.addView(shortnameClear);body.addView(preference);}
        EditText requested=ui.field("Optional requested amount in BNT",false,false);requested.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);body.addView(requested);body.addView(ui.button("Create payment request",false,()->{String amount=requested.getText().toString().trim();try{BigDecimal a=new BigDecimal(amount);if(a.signum()<=0||a.scale()>8)throw new Exception();a.movePointRight(8).longValueExact();String request="blocknet:"+receiveRecipient()+"?amount="+a.stripTrailingZeros().toPlainString();LinearLayout content=dialogBody();ImageView code=new ImageView(this);code.setImageBitmap(qr(request));code.setBackground(ui.box(QrRenderer.BACKGROUND));code.setClipToOutline(true);code.setPadding(ui.dp(10),ui.dp(10),ui.dp(10),ui.dp(10));content.addView(code,new LinearLayout.LayoutParams(-1,ui.dp(280)));content.addView(selectable(request,12));dialog("Payment request",content).setPositiveButton("Share",(d,w)->share(request)).setNegativeButton("Close",null).show();}catch(Exception e){requested.setError(LocalizedText.get(this,"Enter a positive BNT amount with at most 8 decimals."));}}));
        String key=receiveScopeKey();if(!key.equals(receiveEntryKey)){receiveEntryKey=key;receiveVerificationToken++;shortnameChecking=false;receiveForceAddress=true;shortnameNotice="";String saved=repo.savedReceiveShortname();if(!snapshot.cached&&!saved.isEmpty())main.post(()->verifyReceiveShortname(saved,key));}updateReceiveRecipient();
    }
    private Bitmap qr(String value) throws Exception {return QrRenderer.render(this,value,600);}
    private void history(){ui.section(body,snapshot.cached?"last saved activity":"activity");LinearLayout filters=ui.row();for(String filter:new String[]{"all","received","sent"}){Button b=ui.button(filter,false,()->{historyFilter=filter;render();});b.setTextColor(historyFilter.equals(filter)?WalletTheme.LIME:WalletTheme.MUTED);filters.addView(b,new LinearLayout.LayoutParams(0,-2,1));}body.addView(filters);body.addView(ui.text("Incoming entries are wallet outputs. Change and spent outputs may appear alongside received funds.",12,WalletTheme.MUTED));addHistory(body,Integer.MAX_VALUE);if(!snapshot.cached){if((!historyFilter.equals("sent")&&moreOutputs)||(!historyFilter.equals("received")&&moreSends))body.addView(ui.button("Load older activity",false,this::loadOlder));body.addView(ui.button("Refresh activity",false,()->refresh(true)));}}
    private void loadOlder(){
        boolean outputs=!historyFilter.equals("sent")&&moreOutputs,sends=!historyFilter.equals("received")&&moreSends;
        task("Older activity",()->new JSONObject[]{outputs?repo.historyPage(outputOffset):null,sends?repo.sendsPage(sendOffset):null},pages->{
            if(pages[0]!=null){JSONArray entries=pages[0].optJSONArray("outputs");int count=entries==null?0:entries.length();olderOutputs=joined(olderOutputs,entries);outputOffset+=count;moreOutputs=count==100;}
            if(pages[1]!=null){JSONArray entries=pages[1].optJSONArray("sends");int count=entries==null?0:entries.length();olderSends=joined(olderSends,entries);sendOffset+=count;moreSends=count==100;}render();
        });
    }
    private JSONArray joined(JSONArray first,JSONArray second){JSONArray result=new JSONArray();Set<String> seen=new HashSet<>();for(JSONArray array:new JSONArray[]{first,second})if(array!=null)for(int i=0;i<array.length();i++){JSONObject item=array.optJSONObject(i);if(item==null)continue;String key=item.optString("txid",item.toString())+":"+item.optInt("output_index",-1);if(seen.add(key))result.put(item);}return result;}
    private void checkPending(){task("Pending transaction",repo::pendingSendStatus,result->{LinearLayout details=dialogBody();details.addView(ui.text("Review this payment against outgoing activity before resolving an unknown outcome.",13,WalletTheme.ERROR));renderDetails(details,result,0);details.addView(ui.text("Outgoing activity",14,WalletTheme.LIME));String previous=historyFilter;historyFilter="sent";addHistory(details,20);historyFilter=previous;AlertDialog d=dialog("Pending transaction",details).setNegativeButton("Close",null).create();String state=result.optString("state");if(state.equals("not_found")||state.equals("accepted")){details.addView(ui.button("I checked the outcome · resolve manually",false,()->{d.dismiss();new LocalizedText.Dialog(this).setTitle("Resolve unknown payment?").setMessage("Only continue after verifying the address, amount, balance and outgoing history. Resolving enables new payments and does not resend this transaction.").setNegativeButton("Cancel",null).setPositiveButton("Resolve payment",(x,w)->mutate("Resolve payment",()->{repo.acknowledgePendingOutcome();return null;})).show();}));}d.show();secure(d);refresh(false);});}
    static Intent explorerIntent(String txid){if(txid==null||!txid.matches("[0-9a-fA-F]{64}"))return null;return new Intent(Intent.ACTION_VIEW,Uri.parse("https://explorer.blocknetcrypto.com/tx/"+txid));}
    private void addHistory(LinearLayout target,int limit){
        JSONArray outputEntries=joined(snapshot.history,olderOutputs),sendEntries=joined(snapshot.sends,olderSends);List<JSONObject> entries=new ArrayList<>();if(!historyFilter.equals("sent")&&outputEntries!=null)for(int i=0;i<outputEntries.length();i++){JSONObject entry=outputEntries.optJSONObject(i);if(entry!=null)try{JSONObject copy=new JSONObject(entry.toString());copy.put("_direction","received");entries.add(copy);}catch(Exception ignored){}}
        if(!historyFilter.equals("received")&&sendEntries!=null)for(int i=0;i<sendEntries.length();i++){JSONObject entry=sendEntries.optJSONObject(i);if(entry!=null)try{JSONObject copy=new JSONObject(entry.toString());copy.put("_direction","sent");entries.add(copy);}catch(Exception ignored){}}
        entries.sort((a,b)->{long ah=a.optLong("block_height",a.optLong("recorded_height")),bh=b.optLong("block_height",b.optLong("recorded_height"));int height=Long.compare(bh,ah);return height!=0?height:Long.compare(b.optLong("timestamp"),a.optLong("timestamp"));});
        if(entries.isEmpty()){LinearLayout empty=ui.card();empty.addView(ui.text("No activity yet",16,WalletTheme.TEXT));empty.addView(ui.text("Your wallet activity will appear here after the node scans the chain.",13,WalletTheme.MUTED));target.addView(empty);return;}
        for(int i=0;i<Math.min(limit,entries.size());i++){JSONObject entry=entries.get(i);boolean out=entry.optString("_direction").equals("sent");LinearLayout c=ui.card();String state=out?entry.optString("chain_state",entry.optString("status","submitted")):entry.optString("status",entry.optBoolean("spent")?"spent":"received");c.addView(ui.text((out?"↗ Sent":"↙ "+(entry.optBoolean("is_coinbase")?"Mining reward":"Wallet output"))+"   "+coins(entry.optLong("amount",entry.optLong("total_amount")))+" BNT",14,out?WalletTheme.TEXT:WalletTheme.LIME));String tx=entry.optString("txid",entry.optString("tx_hash",""));boolean canExplore=!snapshot.cached&&snapshot.syncAllowed&&explorerIntent(tx)!=null;TextView hash=ui.rawText(shorten(tx)+" · "+LocalizedText.get(this,state),11,canExplore?WalletTheme.LIME:WalletTheme.MUTED);if(canExplore){hash.setPaintFlags(hash.getPaintFlags()|android.graphics.Paint.UNDERLINE_TEXT_FLAG);hash.setClickable(true);hash.setFocusable(true);hash.setMinHeight(ui.dp(48));hash.setGravity(Gravity.CENTER_VERTICAL);hash.setContentDescription(LocalizedText.get(this,"Open transaction "+tx+" in Blocknet explorer"));hash.setOnClickListener(v->{Intent intent=explorerIntent(tx);if(intent!=null)try{startActivity(intent);}catch(ActivityNotFoundException missing){showError("Blocknet explorer",new Exception("No browser is available to open the explorer."));}});}c.addView(hash);if(entry.optLong("block_height")>0)c.addView(ui.text("block "+entry.optLong("block_height"),11,WalletTheme.MUTED));c.setClickable(true);c.setFocusable(true);c.setContentDescription(LocalizedText.get(this,(out?"Sent":"Received")+" transaction "+tx));c.setOnClickListener(v->showJson("Transaction details",entry));target.addView(c);}
    }
    private void settings(){
        languageSection();
        if(snapshot.cached){ui.section(body,"wallet & security");LinearLayout wallet=ui.card();wallet.addView(ui.rawText(repo.activeWallet(),16,WalletTheme.TEXT));wallet.addView(ui.text("Last saved view · encrypted local wallet",12,WalletTheme.MUTED));wallet.addView(ui.button("Address book",false,()->route("addressbook")));wallet.addView(ui.button("Switch wallet",false,this::chooseWallet));wallet.addView(ui.button("Lock wallet",false,this::lockWallet));body.addView(wallet);ui.section(body,"node");LinearLayout node=ui.card();networkPolicyStatus(node);mobileDataSetting(node);node.addView(syncRunButton(()->{}));node.addView(ui.button("Node settings & diagnostics",false,this::nodeSettings));body.addView(node);updateSection();return;}
        ui.section(body,"wallet & security");LinearLayout wallet=ui.card();wallet.addView(ui.rawText(repo.activeWallet(),16,WalletTheme.TEXT));wallet.addView(ui.text(snapshot.viewOnly?"View-only wallet":"Encrypted local wallet",12,WalletTheme.MUTED));wallet.addView(ui.button("Address book",false,()->route("addressbook")));wallet.addView(ui.button("Switch / add wallet",false,this::chooseWallet));biometricSetting(wallet);wallet.addView(ui.button("Export encrypted backup",false,this::exportWallet));if(!snapshot.viewOnly)wallet.addView(ui.button("Show recovery phrase",false,this::revealSeed));wallet.addView(ui.button("Lock wallet",false,this::lockWallet));body.addView(wallet);
        ui.section(body,"node");LinearLayout node=ui.card();ui.line(node,"Network",snapshot.network);ui.line(node,"Chain height",Long.toString(snapshot.height));ui.line(node,"Wallet height",Long.toString(snapshot.walletHeight));ui.line(node,"Peers",Integer.toString(snapshot.peers));nodeSyncStatus(node);mobileDataSetting(node);node.addView(ui.button("Node settings & diagnostics",false,this::nodeSettings));node.addView(ui.button("Rescan wallet",false,()->new LocalizedText.Dialog(this).setTitle("Rescan wallet?").setMessage("The wallet will scan the chain again to find your outputs. This can take time.").setNegativeButton("Cancel",null).setPositiveButton("Rescan",(d,w)->mutate("Rescan",()->{repo.rescan();return null;})).show()));body.addView(node);
        ui.section(body,"transaction alerts");LinearLayout alerts=ui.card();transactionAlertSettings(alerts);body.addView(alerts);
        ui.section(body,"message tools");body.addView(ui.button("Sign a message",false,()->messageTool(true)));body.addView(ui.button("Verify a signature",false,()->messageTool(false)));
        updateSection();ui.section(body,"about");body.addView(ui.text("blocknet / native Android\nBundled Core "+BuildConfig.CORE_VERSION+"\nZero-knowledge money.",13,WalletTheme.MUTED));body.addView(ui.button("Visit blocknetcrypto.com",false,()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://blocknetcrypto.com/")))));
    }
    private void languageSection(){ui.section(body,"Language");LinearLayout card=ui.card();card.addView(ui.button("Language / 语言",false,this::chooseLanguage));body.addView(card);}
    private void chooseLanguage(){
        new LocalizedText.Dialog(this).setTitle("Language")
            .setMessage("Changing language locks the wallet. Unlock it again to continue.")
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Continue",(dialog,which)->showLanguageChoices()).show();
    }
    private void showLanguageChoices(){
        String[] choices={"Follow phone language","English","Simplified Chinese"};
        String[] values={AppLanguage.SYSTEM,AppLanguage.ENGLISH,AppLanguage.CHINESE};
        new LocalizedText.Dialog(this).setTitle("Language")
            .setItems(choices,(dialog,which)->{
                String choice=values[which];if(choice.equals(AppLanguage.choice(this)))return;
                cancelBookLookups();task("Language",()->{repo.lock();return true;},ignored->
                    LanguageChangeFlow.complete(()->{
                        OfflineSnapshotStore.lock();clearSecrets(body);
                        for(AlertDialog open:new ArrayList<>(sensitiveDialogs))open.dismiss();
                        snapshot=null;page="overview";render();
                    },()->{
                        if(!AppLanguage.set(this,choice))throw new IOException("Could not save language choice.");
                    },this::recreate));
            }).setNegativeButton("Cancel",null).show();
    }
    private void checkUpdates(boolean manual){
        if(updateChecking)return;
        updateChecking=true;if(manual&&page.equals("settings"))render();
        updateWorker.execute(()->{SoftwareUpdateChecker.Result result=null;Exception failure=null;
            try {result=new SoftwareUpdateChecker(this).check(manual);}catch(Exception error){failure=error;}
            final SoftwareUpdateChecker.Result checked=result;final Exception problem=failure;
            main.post(()->{if(destroyed)return;updateChecking=false;
                if(manual)updateMessage=problem!=null?(problem.getMessage()==null?"Update check failed.":problem.getMessage()):BuildConfig.ANNOUNCEMENT_ENABLED?(checked.available==null?"No newer signed notice accepted yet. Chain catch-up and manifest retries continue when sync is available.":"Signed on-chain release notice: "+checked.available.tag):(checked.available==null?"No newer preview with this build's APK variant was found.":"Unverified GitHub preview listing: "+checked.available.tag);
                if(page.equals("settings")||(snapshot!=null&&!snapshot.loaded)||(checked!=null&&checked.available!=null&&page.equals("overview")))render();
            });
        });
    }
    private void updateSection(){
        ui.section(body,"software updates");LinearLayout updates=ui.card();
        updates.addView(ui.text("Update comparison baseline: "+BuildConfig.RELEASE_TAG,12,WalletTheme.MUTED));
        long checked=new SoftwareUpdateChecker(this).lastChecked();
        if(checked>0)updates.addView(ui.text("Last checked: "+AppLanguage.formatDate(this,checked,java.text.DateFormat.MEDIUM,java.text.DateFormat.MEDIUM),12,WalletTheme.MUTED));
        updates.addView(ui.text(BuildConfig.ANNOUNCEMENT_ENABLED?
            "Signed announcements are scanned from current mainnet blocks over a permitted sync connection. This verifies the notice and manifest, not the APK on the release page.":
            "Legacy GitHub listings are unverified. Daily checks use only a permitted sync connection.",12,WalletTheme.MUTED));
        updates.addView(ui.text("Back up your wallet and verify recovery before upgrading.",14,WalletTheme.MUTED));
        if(!updateMessage.isEmpty())updates.addView(ui.text(updateMessage,12,WalletTheme.MUTED));
        Button check=ui.button(updateChecking?"Checking for updates…":"Check for updates",false,()->checkUpdates(true));check.setEnabled(!updateChecking);updates.addView(check);
        SoftwareUpdateChecker.Release release=new SoftwareUpdateChecker(this).saved();
        if(release!=null){updates.addView(ui.text((release.signedAnnouncement?"Signed on-chain release notice: ":"Unverified GitHub preview listing: ")+release.tag,14,WalletTheme.LIME));
            if(!release.notes.isEmpty())updates.addView(ui.rawText(release.notes,12,WalletTheme.MUTED));
            updates.addView(ui.button("Open official release page",false,()->new LocalizedText.Dialog(this)
                .setTitle("Open official release page?")
                .setMessage("This opens the official release page in your browser. The wallet does not download, authenticate, or install the APK there.")
                .setNegativeButton("Cancel",null).setPositiveButton("Open page",(dialog,which)->{
                    SyncNetworkPolicy.State connection=SyncNetworkPolicy.read(this);
                    if(!connection.allowed){showError("Software update",new Exception(connection.reason));return;}
                    try {startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(release.url)));}
                    catch(ActivityNotFoundException missing){showError("Software update",new Exception("No browser is available to open the release page."));}
                }).show()));
        }
        body.addView(updates);
    }
    private Switch alertSwitch(String title,boolean checked,Set<Switch> tracked){
        Switch control=new Switch(this);control.setText(LocalizedText.get(this,title));control.setTextColor(WalletTheme.TEXT);control.setTextSize(14);control.setTypeface(Typeface.MONOSPACE);control.setMinHeight(ui.dp(54));control.setChecked(checked);control.setContentDescription(LocalizedText.get(this,title));tracked.add(control);control.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){}public void onViewDetachedFromWindow(View v){tracked.remove(control);}});return control;
    }
    private void transactionAlertSettings(LinearLayout target){
        Switch enabled=alertSwitch("Transaction alerts",TransactionNotifier.enabled(this),alertEnabledControls);target.addView(enabled);
        enabled.setOnCheckedChangeListener((button,value)->{if(updatingAlertControls)return;updatingAlertControls=true;enabled.setChecked(TransactionNotifier.enabled(this));updatingAlertControls=false;if(busy)return;task("Transaction alerts",()->{TransactionNotifier.setEnabled(this,value);return value;},on->{render();if(on&&Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},504);});});
        Switch sound=alertSwitch("Sound for transaction alerts",TransactionNotifier.soundEnabled(this),alertSoundControls);sound.setEnabled(TransactionNotifier.enabled(this));target.addView(sound);
        sound.setOnCheckedChangeListener((button,value)->{if(updatingAlertControls)return;updatingAlertControls=true;sound.setChecked(TransactionNotifier.soundEnabled(this));updatingAlertControls=false;if(busy)return;task("Transaction alert sound",()->{TransactionNotifier.setSoundEnabled(this,value);return value;},on->render());});
        target.addView(ui.text(BuildConfig.CORE_ACTIVITY_API?"After you open a wallet, alerts can continue while it is locked. The node must be running and the wallet scan caught up. Sound is optional; Android notification settings and device volume also control playback.":"Alerts notify you about new incoming and outgoing wallet activity while the node is running and the wallet is unlocked. Sound is optional; Android notification channels and device volume also control playback.",12,WalletTheme.MUTED));
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)target.addView(ui.text("Android notifications are currently blocked. Allow notifications in system settings to receive transaction alerts.",12,WalletTheme.ERROR));
        target.addView(ui.button("Android notification settings",false,()->{Intent intent=new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName());startActivity(intent);}));
    }
    private void nodeSettings(){
        LinearLayout content=dialogBody();content.addView(ui.text("The bundled core runs locally in a foreground service. Chain sync uses network data, storage and battery.",13,WalletTheme.MUTED));content.addView(ui.text("Network: "+repo.network(),14,WalletTheme.LIME));content.addView(ui.text("Free app storage: "+android.text.format.Formatter.formatFileSize(this,getNoBackupFilesDir().getUsableSpace()),12,WalletTheme.MUTED));content.addView(ui.text("First sync may take time. Keep free storage available as the blockchain grows. A 64-bit ARM device running Android 8 or later is required.",12,WalletTheme.MUTED));if(snapshot!=null&&snapshot.nodeMessage!=null&&!snapshot.nodeMessage.equals("Local node running"))content.addView(ui.text(snapshot.nodeMessage,12,WalletTheme.MUTED));
        nodeSyncStatus(content);mobileDataSetting(content);
        if(BuildConfig.CORE_MOBILE_CLIENT){
            Switch foregroundOnly=new Switch(this);foregroundOnly.setText(LocalizedText.get(this,"Stop node when wallet leaves foreground"));foregroundOnly.setTextColor(WalletTheme.TEXT);foregroundOnly.setChecked(MobileCorePolicy.enabled(this));
            foregroundOnly.setOnCheckedChangeListener((button,enabled)->mutate("Mobile node policy",()->{MobileCorePolicy.setEnabled(this,enabled);return null;}));
            content.addView(foregroundOnly);
            content.addView(ui.text("Stops Core and background payment alerts. Reopening the wallet restarts Core and scans from saved state; large chains can make each restart slow. Saved balances remain dated until catch-up. Battery savings have not been measured.",12,WalletTheme.MUTED));
        }
        AlertDialog d=dialog("Node settings",content).setNegativeButton("Close",null).create();content.addView(syncRunButton(d::dismiss));
        if(repo.network().equals("mainnet")&&snapshot.syncAllowed&&!snapshot.cached)content.addView(ui.button("Refresh checkpoints",false,()->{d.dismiss();mutate("Refresh checkpoints",repo::refreshCheckpoints);}));
        content.addView(ui.button("Change network",false,()->{d.dismiss();new LocalizedText.Dialog(this).setTitle("Choose network").setMessage("The node will restart. Mainnet and testnet wallets are stored separately.").setPositiveButton("Mainnet",(x,w)->{cancelBookLookups();mutate("Network",()->{repo.setNetwork("mainnet");return null;});}).setNeutralButton("Testnet",(x,w)->{cancelBookLookups();mutate("Network",()->{repo.setNetwork("testnet");return null;});}).setNegativeButton("Cancel",null).show();}));
        content.addView(ui.button("Show diagnostics",false,()->{d.dismiss();task("Diagnostics",repo::diagnostics,text->{LinearLayout report=dialogBody();report.addView(selectable(text,11));dialog("Node diagnostics",report).setNegativeButton("Close",null).setPositiveButton("Share report",(x,w)->share(text)).show();});}));content.addView(ui.button("View local node log",false,()->{d.dismiss();task("Node log",repo::nodeLog,text->{LinearLayout log=dialogBody();log.addView(selectable(text,11));dialog("Local node log",log).setNegativeButton("Close",null).show();});}));d.show();secure(d);
    }
    private void messageTool(boolean signing){
        LinearLayout content=dialogBody();EditText address=ui.field("Signer public address",false,true),message=ui.field("Message",false,true),signature=ui.field("Signature",false,true);if(!signing)content.addView(address);content.addView(message);if(!signing)content.addView(signature);AlertDialog d=dialog(signing?"Sign message":"Verify signature",content).setNegativeButton("Cancel",null).setPositiveButton(signing?"Sign":"Verify",null).create();d.show();secure(d);d.getButton(-1).setOnClickListener(v->{String text=message.getText().toString(),a=address.getText().toString().trim(),s=signature.getText().toString().trim();if(text.isEmpty()){message.setError(LocalizedText.get(this,"Enter a message"));return;}d.dismiss();task(signing?"Sign message":"Verify signature",()->signing?repo.sign(text):repo.verify(a,text,s),result->showJson(signing?"Signed message":"Signature verification",result));});
    }
    private void importWallet(){
        LinearLayout form=dialogBody();form.addView(ui.text("Choose an encrypted Blocknet wallet backup. Import creates a new wallet file on this device.",13,WalletTheme.MUTED));EditText name=ui.field("Name for imported wallet",false,false);form.addView(name);AlertDialog d=dialog("Import wallet backup",form).setNegativeButton("Cancel",null).setPositiveButton("Choose file",null).create();d.show();d.getButton(-1).setOnClickListener(v->{String n=name.getText().toString().trim();if(n.trim().isEmpty()){name.setError(LocalizedText.get(this,"Enter a wallet name"));return;}pendingImportName=n;d.dismiss();Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");startActivityForResult(intent,501);});
    }
    private void exportWallet(){Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/octet-stream");intent.putExtra(Intent.EXTRA_TITLE,"blocknet-"+repo.activeWallet()+".backup");startActivityForResult(intent,502);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;if(request==503){String payment=data.getStringExtra("payment");if(payment!=null){paymentText=payment;if(resumed&&snapshot!=null&&snapshot.loaded&&!snapshot.locked)showSend();}return;}Uri uri=data.getData();if(uri==null)return;if(request==501){String name=pendingImportName;task("Import wallet",()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Cannot read selected file");repo.importBackup(in,name);}return repo.wallets();},list->{new LocalizedText.Dialog(this).setTitle("Backup imported").setMessage("Your encrypted wallet file is ready. Open it using its original password.").setPositiveButton("Open wallet",(d,w)->chooseWallet()).show();});}else if(request==502){task("Export backup",()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("Cannot write to selected file");repo.exportBackup(out);}repo.markBackupComplete();return true;},ok->{refresh(true);new LocalizedText.Dialog(this).setTitle("Backup exported").setMessage("Your wallet backup is encrypted with your wallet password. Store both safely. The node was stopped to flush a consistent backup. Restart it and unlock your wallet to continue.").setPositiveButton("Done",null).show();});}}
    private LinearLayout dialogBody(){LinearLayout layout=ui.column();layout.setPadding(ui.dp(20),ui.dp(10),ui.dp(20),ui.dp(16));layout.setBackgroundColor(WalletTheme.BG);return layout;}
    private AlertDialog.Builder dialog(String title,LinearLayout content){ScrollView scroll=new ScrollView(this);scroll.addView(content);return new LocalizedText.Dialog(this){@Override public AlertDialog create(){AlertDialog d=super.create();openDialogs.add(d);boolean sensitive=title.equals("Recovery phrase")||title.equals("Confirm backup")||title.equals("Confirm payment")||title.equals("Reveal recovery phrase")||title.equals("Create wallet")||title.equals("Restore wallet")||title.equals("Import keys")||title.equals("Enable biometric unlock")||title.startsWith("Open ")||title.equals("Sign message")||title.equals("Enable biometric unlock")||title.equals("Unlock wallet")||title.equals("Add recipient")||title.equals("Edit recipient")||title.equals("Select recipient")||title.equals("Delete recipient")||title.equals("Saved shortname needs attention");if(sensitive)sensitiveDialogs.add(d);d.setOnDismissListener(x->{if(d==sendDialog)sendDialog=null;sensitiveDialogs.remove(d);openDialogs.remove(d);clearEdits(content);if(sensitive)content.removeAllViews();});return d;}}.setTitle(title).setView(scroll);}
    private void clearSecrets(View view){if(view==null)return;if(view instanceof EditText&&"wallet-secret".equals(view.getTag()))((EditText)view).setText("");else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)clearSecrets(group.getChildAt(i));}}
    private void clearEdits(View view){if(view instanceof EditText)((EditText)view).setText("");else if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)clearEdits(group.getChildAt(i));}}
    private void secure(AlertDialog dialog){if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);}
    private TextView selectable(String value,int size){TextView t=ui.rawText(value,size,WalletTheme.TEXT);t.setTextIsSelectable(true);return t;}
    private void showJson(String title,JSONObject json){LinearLayout content=dialogBody();renderDetails(content,json,0);dialog(title,content).setNegativeButton("Close",null).setPositiveButton("Copy details",(d,w)->copy(title,json.toString())).show();}
    private void renderDetails(LinearLayout content,JSONObject json,int depth){
        if(depth>4)return;
        Iterator<String> keys=json.keys();while(keys.hasNext()){String key=keys.next();if(key.startsWith("_"))continue;Object value=json.opt(key);if(value==null||value==JSONObject.NULL)continue;
            String label=key.equals("txid")?"Transaction ID":key.replace('_',' ');if(!label.isEmpty())label=Character.toUpperCase(label.charAt(0))+label.substring(1);
            if(value instanceof JSONObject){content.addView(ui.text(label,12,WalletTheme.LIME));renderDetails(content,(JSONObject)value,depth+1);}
            else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;content.addView(ui.text(label,12,WalletTheme.LIME));for(int i=0;i<array.length();i++){Object item=array.opt(i);if(item instanceof JSONObject)renderDetails(content,(JSONObject)item,depth+1);else content.addView(selectable(String.valueOf(item),12));}}
            else {String text=String.valueOf(value);if(value instanceof Number&&(key.equals("amount")||key.equals("total_amount")||key.equals("fee")||key.equals("change")))text=coins(((Number)value).longValue())+" BNT";content.addView(ui.text(label,11,WalletTheme.MUTED));content.addView(selectable(text,13));}
        }
    }
    private void copy(String label,String value){android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText(label,value));Toast.makeText(this,LocalizedText.get(this,"Copied"),Toast.LENGTH_SHORT).show();}
    private void share(String value){Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TEXT,value);startActivity(Intent.createChooser(intent,LocalizedText.get(this,"Share Blocknet")));}
    private static String coins(long atomic){return BigDecimal.valueOf(atomic,8).stripTrailingZeros().toPlainString();}
    private static String shorten(String text){return text.length()>22?text.substring(0,12)+"…"+text.substring(text.length()-8):text;}
}
