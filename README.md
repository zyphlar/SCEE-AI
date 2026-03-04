# Voice Mapper for SCEE

A voice-based mapping feature for SCEE (StreetComplete Expert Edition) that allows hands-free addition, modification, and removal of map elements while driving, cycling, or walking.

## Features

- **Voice Recognition**: Uses Android's built-in speech recognition for hands-free operation
- **AI-Powered Understanding**: Leverages Claude AI to understand natural language commands and convert them to proper OSM tags
- **Relative Positioning**: Automatically places POIs based on "left/right of road" and distance estimates
- **Brand Recognition**: Knows hundreds of common brands (fast food, gas stations, retail) with proper Wikidata identifiers
- **Address Handling**: Parses addresses and associates them with POIs
- **Confirmation Flow**: Review and edit pending changes before they're applied
- **Continuous Mode**: Long-press for continuous voice input while driving
- **Audio Feedback**: Optional TTS confirmation of actions

## Voice Command Examples

### Adding POIs
```
"McDonald's on the left"
"On the right, Shell gas station"
"On the left, KFC, Taco Bell, Wendy's with addresses 123, 125, 127"
"Bench about 20 meters back on the left"
"Fire hydrant on the right"
```

### With Details
```
"Restaurant Italian cuisine on the left"
"ATM Chase bank on the right"
"Gas station, brand is BP, on the left"
```

### Modifications
```
"The bakery is now a restaurant"
"Change the name of the shop to Bob's Groceries"
```

### Deletions
```
"Remove the ATM, it doesn't exist"
"Delete the bench marker"
```

## Integration Guide

### 1. Add Dependencies

Add to your `app/build.gradle.kts`:

```kotlin
dependencies {
    // Existing dependencies...
    
    // For voice mapper
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}
```

### 2. Add Permissions

Add to `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

### 3. Copy Source Files

Copy the entire `voicemapper` package to:
```
app/src/main/java/de/westnordost/streetcomplete/voicemapper/
```

### 4. Copy Resources

Copy the layout and drawable files:
```
app/src/main/res/layout/fragment_voice_mapper.xml
app/src/main/res/layout/item_pending_edit.xml
app/src/main/res/layout/item_tag.xml
app/src/main/res/layout/dialog_edit_voice_mapper.xml
app/src/main/res/layout/dialog_add_tag.xml
app/src/main/res/layout/dialog_voice_mapper_settings.xml

app/src/main/res/drawable/bg_voice_button.xml
app/src/main/res/drawable/bg_continuous_indicator.xml
app/src/main/res/drawable/bg_chip.xml
app/src/main/res/drawable/bg_edit_underline.xml
app/src/main/res/drawable/ic_mic.xml
app/src/main/res/drawable/ic_mic_active.xml
app/src/main/res/drawable/ic_check.xml
app/src/main/res/drawable/ic_close.xml
app/src/main/res/drawable/ic_edit.xml
app/src/main/res/drawable/ic_settings.xml
app/src/main/res/drawable/ic_help.xml

app/src/main/res/values/colors_voice_mapper.xml
```

### 5. Register Koin Module

In your Application class, add the voiceMapperModule:

```kotlin
// In StreetCompleteApplication.kt or equivalent

startKoin {
    androidContext(this@StreetCompleteApplication)
    modules(
        // ... existing modules
        voiceMapperModule
    )
}
```

### 6. Add API Key Setting

Add a preference for the Anthropic API key in your settings:

```kotlin
// In preferences
<EditTextPreference
    android:key="anthropic_api_key"
    android:title="Claude API Key"
    android:summary="Required for AI-powered voice mapping"
    android:inputType="textPassword" />
```

### 7. Add Voice Mapper Button to Main Screen

In `MainActivity.kt` or your main map fragment:

```kotlin
// Add a floating action button or menu item
voiceMapperButton.setOnClickListener {
    supportFragmentManager.beginTransaction()
        .add(R.id.fragment_container, VoiceMapperFragment.newInstance())
        .addToBackStack("voice_mapper")
        .commit()
}
```

### 8. Connect Location Updates

Forward location updates to the VoiceMapperService:

```kotlin
// In your location listener
locationManager.addLocationListener { location ->
    voiceMapperService.updateLocation(location, location.bearing)
}
```

## Configuration

### Settings Available

- **Audio Feedback**: Enable/disable TTS confirmations
- **Auto-confirm High Confidence**: Automatically apply edits with ≥90% confidence in continuous mode
- **Default Side**: Which side to place POIs when not specified
- **Default Distance**: Distance from road center (in meters)

### Offline Mode

The voice mapper includes local parsing for common patterns (brand names, basic POIs) that works without an API connection. Complex commands require the Claude API.

## Architecture

```
voicemapper/
├── VoiceMapperService.kt      # Main service - speech recognition & coordination
├── VoiceMapperAIProcessor.kt  # AI processing with Claude API
├── VoiceMapperModels.kt       # Data classes, OSM feature mappings
├── VoiceMapperActions.kt      # Edit actions (create, modify, delete nodes)
├── VoiceMapperFragment.kt     # UI fragment
├── VoiceMapperViewModel.kt    # ViewModel for UI state
├── VoiceMapperEditDialog.kt   # Edit confirmation dialog
├── PendingEditsAdapter.kt     # RecyclerView adapter
├── VoiceMapperQuestType.kt    # Quest type for changesets
└── VoiceMapperModule.kt       # Koin DI module
```

## OSM Tagging

The voice mapper generates proper OSM tags including:

- Standard amenity/shop tags
- Brand names with brand:wikidata
- Address components (addr:housenumber, addr:street)
- Cuisine for restaurants/fast food
- Additional contextual tags

### Brand Database

Includes 100+ brands across categories:
- Fast food (McDonald's, KFC, Taco Bell, etc.)
- Gas stations (Shell, BP, Exxon, etc.)
- Retail (Walmart, Target, CVS, etc.)
- Banks (Chase, Bank of America, etc.)
- Hotels (Marriott, Hilton, Holiday Inn, etc.)

## Changeset Tags

Edits made via voice mapper use:
- `created_by`: StreetComplete_ee with VoiceMapper
- `comment`: "Voice mapped POIs"
- `source`: survey

## Known Limitations

1. **GPS Accuracy**: Position accuracy depends on GPS signal quality
2. **Speech Recognition**: Works best in quiet environments
3. **Bearing**: Requires device to provide accurate bearing (works best when moving)
4. **Complex Edits**: Way modifications and relation edits not yet supported
5. **Language**: Currently English-focused; other languages require AI API

## Contributing

Contributions welcome! Areas for improvement:
- Additional brand/POI database entries
- Multi-language support
- Enhanced offline capabilities
- Way editing support
- Photo attachment

## License

GPL-3.0 (same as SCEE)

## Credits

- SCEE by Helium314
- StreetComplete by Tobias Zwick
- Claude AI by Anthropic
