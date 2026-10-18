package com.blocknet.wallet;

/** After the wallet locks, remove sensitive UI before attempting a locale write. */
final class LanguageChangeFlow {
    interface Save { void run() throws Exception; }
    private LanguageChangeFlow(){}
    static void complete(Runnable redact,Save save,Runnable recreate) throws Exception {
        redact.run();
        save.run();
        recreate.run();
    }
}
