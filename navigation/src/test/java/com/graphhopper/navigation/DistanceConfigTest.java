package com.graphhopper.navigation;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import com.graphhopper.routing.util.TransportationMode;
import com.graphhopper.util.Translation;
import com.graphhopper.util.TranslationMap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Locale;

public class DistanceConfigTest {

    @Test
    public void distanceConfigTest() {
        // from TransportationMode
        DistanceConfig car = new DistanceConfig(DistanceUtils.Unit.METRIC, null, null, TransportationMode.CAR);
        assertEquals(4, car.voiceInstructions.size());
        DistanceConfig foot = new DistanceConfig(DistanceUtils.Unit.METRIC, null, null, TransportationMode.FOOT);
        assertEquals(1, foot.voiceInstructions.size());
        DistanceConfig bike = new DistanceConfig(DistanceUtils.Unit.METRIC, null, null, TransportationMode.BIKE);
        assertEquals(1, bike.voiceInstructions.size());
        DistanceConfig bus = new DistanceConfig(DistanceUtils.Unit.METRIC, null, null, TransportationMode.BUS);
        assertEquals(4, bus.voiceInstructions.size());

        // from Profile
        GraphHopper hopper = new GraphHopper().setProfiles(
                new Profile("my_truck"),
                new Profile("foot"),
                new Profile("ebike").putHint("navigation_mode", "bike"));
        assertEquals(TransportationMode.CAR, hopper.getNavigationMode("unknown"));
        assertEquals(TransportationMode.CAR, hopper.getNavigationMode("my_truck"));
        assertEquals(TransportationMode.FOOT, hopper.getNavigationMode("foot"));
        assertEquals(TransportationMode.BIKE, hopper.getNavigationMode("ebike"));

        // from String
        DistanceConfig driving = new DistanceConfig(DistanceUtils.Unit.METRIC, null, null, "driving");
        assertEquals(4, driving.voiceInstructions.size());
        DistanceConfig anything = new DistanceConfig(DistanceUtils.Unit.METRIC, null, null, "anything");
        assertEquals(4, anything.voiceInstructions.size());
        DistanceConfig none = new DistanceConfig(DistanceUtils.Unit.METRIC, null, null, "");
        assertEquals(4, none.voiceInstructions.size());
        DistanceConfig biking = new DistanceConfig(DistanceUtils.Unit.METRIC, null, null, "biking");
        assertEquals(1, biking.voiceInstructions.size());
    }
    
    @Test
    void getVoiceInstructionsForDistance_usesTranslationMapAndTranslation() {
        // Arrange : mocks des dépendances
        TranslationMap translationMap = mock(TranslationMap.class);
        Translation translation = mock(Translation.class);
        Locale locale = Locale.ENGLISH;

        when(translationMap.getWithFallBack(locale)).thenReturn(translation);
        // On ne dépend pas du texte réel, donc on peut renvoyer une chaîne fixe
        when(translation.tr(anyString(), any())).thenReturn("Mocked instruction");

        DistanceConfig config = new DistanceConfig(
                DistanceUtils.Unit.METRIC,
                translationMap,
                locale,
                TransportationMode.CAR
        );

        // Act : on demande des instructions pour une distance où des annonces sont attendues
        List<VoiceInstructionConfig.VoiceInstructionValue> instructions =
                config.getVoiceInstructionsForDistance(2500, "turn left", "");

        // Assert
        assertNotNull(instructions);
        assertFalse(instructions.isEmpty(), "On doit obtenir au moins une instruction vocale");
        // Vérifie l'utilisation des mocks
        verify(translationMap, atLeastOnce()).getWithFallBack(locale);
        verify(translation, atLeastOnce()).tr(anyString(), any());
    }

}
