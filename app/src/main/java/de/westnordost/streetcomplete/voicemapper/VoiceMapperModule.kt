package de.westnordost.streetcomplete.voicemapper

import android.content.Context
import de.westnordost.streetcomplete.Prefs
import io.ktor.client.HttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val voiceMapperModule = module {

    single {
        val endpoint = Prefs.sharedPreferences.getString(
            VoiceMapperPrefs.KEY_API_ENDPOINT,
            "https://api.anthropic.com/v1/messages"
        ) ?: "https://api.anthropic.com/v1/messages"

        VoiceMapperAIProcessor(endpoint, get())
    }

    single {
        VoiceMapperService(androidContext())
    }

    single { VoiceMapperOverlay(get()) }

    viewModel {
        VoiceMapperViewModel(get())
    }
}

fun Context.initializeVoiceMapper() {
    // Any additional initialization can go here
}
