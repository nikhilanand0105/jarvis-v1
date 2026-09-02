package com.jarvispoc

import android.app.Application
import androidx.appfunctions.AppFunctionConfiguration
import com.jarvispoc.ai.LocalLlmEngines
import com.jarvispoc.appfunctions.AmazonFunctions
import com.jarvispoc.appfunctions.DraftingFunctions
import com.jarvispoc.appfunctions.IntentFunctions
import com.jarvispoc.appfunctions.MessagingFunctions

class JarvisApplication : Application(), AppFunctionConfiguration.Provider {

    override val appFunctionConfiguration: AppFunctionConfiguration by lazy {
        val llm = LocalLlmEngines.shared(this)
        AppFunctionConfiguration.Builder()
            .addEnclosingClassFactory(IntentFunctions::class.java) { IntentFunctions(llm) }
            .addEnclosingClassFactory(AmazonFunctions::class.java) { AmazonFunctions() }
            .addEnclosingClassFactory(DraftingFunctions::class.java) { DraftingFunctions(llm) }
            .addEnclosingClassFactory(MessagingFunctions::class.java) { MessagingFunctions(this) }
            .build()
    }
}
