package de.westnordost.streetcomplete.overlays

import com.russhwolf.settings.ObservableSettings
import de.westnordost.countryboundaries.CountryBoundaries
import de.westnordost.streetcomplete.ApplicationConstants.EE_QUEST_OFFSET
import de.westnordost.osmfeatures.Feature
import de.westnordost.osmfeatures.FeatureDictionary
import de.westnordost.streetcomplete.data.meta.CountryInfo
import de.westnordost.streetcomplete.data.meta.CountryInfos
import de.westnordost.streetcomplete.data.meta.getByLocation
import de.westnordost.streetcomplete.data.osm.mapdata.Element
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.data.overlays.OverlayRegistry
import de.westnordost.streetcomplete.overlays.custom.CustomOverlay
import de.westnordost.streetcomplete.overlays.address.AddressOverlay
import de.westnordost.streetcomplete.overlays.buildings.BuildingsOverlay
import de.westnordost.streetcomplete.overlays.cycleway.CyclewayOverlay
import de.westnordost.streetcomplete.overlays.mtb_scale.MtbScaleOverlay
import de.westnordost.streetcomplete.overlays.places.PlacesOverlay
//import de.westnordost.streetcomplete.overlays.restriction.RestrictionOverlay
import de.westnordost.streetcomplete.overlays.sidewalk.SidewalkOverlay
import de.westnordost.streetcomplete.overlays.street_parking.StreetParkingOverlay
import de.westnordost.streetcomplete.overlays.surface.SurfaceOverlay
import de.westnordost.streetcomplete.overlays.things.ThingsOverlay
import de.westnordost.streetcomplete.overlays.way_lit.WayLitOverlay
import de.westnordost.streetcomplete.util.ktx.getFeature
import de.westnordost.streetcomplete.util.ktx.getIds
import de.westnordost.streetcomplete.voicemapper.VoiceMapperOverlay
import org.koin.core.qualifier.named
import org.koin.dsl.module

/* Each overlay is assigned an ordinal. This is used for serialization and is thus never changed,
*  even if the order of overlays is changed.  */
val overlaysModule = module {
    single {
        overlaysRegistry(
            { location ->
                val countryInfos = get<CountryInfos>()
                val countryBoundaries = get<Lazy<CountryBoundaries>>(named("CountryBoundariesLazy")).value
                countryInfos.getByLocation(countryBoundaries, location.longitude, location.latitude)
            },
            { location ->
                val countryBoundaries = get<Lazy<CountryBoundaries>>(named("CountryBoundariesLazy")).value
                countryBoundaries.getIds(location).firstOrNull()
            },
            { element ->
                get<Lazy<FeatureDictionary>>(named("FeatureDictionaryLazy")).value.getFeature(element)
            },
            get(),
            get<VoiceMapperOverlay>(),
        )
    }
}

fun overlaysRegistry(
    getCountryInfoByLocation: (LatLon) -> CountryInfo,
    getCountryCodeByLocation: (LatLon) -> String?,
    getFeature: (Element) -> Feature?,
    prefs: ObservableSettings,
    voiceMapperOverlay: VoiceMapperOverlay? = null,
) = OverlayRegistry(buildList {
    add(0 to WayLitOverlay())
    add(6 to SurfaceOverlay())
    add(1 to SidewalkOverlay())
    add(5 to CyclewayOverlay(getCountryInfoByLocation))
    add(2 to StreetParkingOverlay())
    add(3 to AddressOverlay(getCountryCodeByLocation))
    add(4 to PlacesOverlay(getFeature))
    add(8 to ThingsOverlay(getFeature))
    add(7 to BuildingsOverlay())
    add(9 to MtbScaleOverlay())
//    add((EE_QUEST_OFFSET + 1) to RestrictionOverlay())
    if (voiceMapperOverlay != null) add(10 to voiceMapperOverlay)
    add((EE_QUEST_OFFSET + 0) to CustomOverlay(prefs))
})
