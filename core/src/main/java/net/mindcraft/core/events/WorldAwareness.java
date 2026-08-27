package net.mindcraft.core.events;

import net.mindcraft.core.world.SelfState;

import java.util.Optional;

/**
 * Turns continuous world state (time of day, weather, dimension) into rare,
 * discrete semantic events on transitions — sunset once a day, weather
 * changes, dimension hops. Transitions are naturally rate-limited, so they
 * can go straight to the event log without per-tick spam.
 */
public final class WorldAwareness {

    public enum TimePhase { MORNING, AFTERNOON, SUNSET, NIGHT }

    private TimePhase lastPhase;
    private String lastWeather;
    private String lastDimension;

    /** Advance with the latest self-state; returns at most one event. */
    public Optional<SemanticEvent> tick(SelfState self) {
        TimePhase phase = phaseOf(self.timeOfDay());
        Optional<SemanticEvent> event = Optional.empty();

        if (known(lastDimension) && known(self.dimension()) && !self.dimension().equals(lastDimension)) {
            event = Optional.of(SemanticEvent.of(EventPriority.P4, "DIMENSION_CHANGE",
                    "You entered " + self.dimension() + "."));
        } else if (known(lastWeather) && known(self.weather()) && !self.weather().equals(lastWeather)) {
            event = Optional.of(SemanticEvent.of(EventPriority.P5, "WEATHER_CHANGE",
                    "The weather changed to " + self.weather() + "."));
        } else if (phase != null && lastPhase != null && phase != lastPhase) {
            if (phase == TimePhase.SUNSET) {
                event = Optional.of(SemanticEvent.of(EventPriority.P5, "SUNSET",
                        "The sun is setting; night is coming."));
            } else if (phase == TimePhase.NIGHT) {
                event = Optional.of(SemanticEvent.of(EventPriority.P5, "NIGHT",
                        "It is night."));
            } else if (phase == TimePhase.MORNING && lastPhase == TimePhase.NIGHT) {
                event = Optional.of(SemanticEvent.of(EventPriority.P5, "DAWN",
                        "The sun is rising."));
            }
        }

        if (phase != null) lastPhase = phase;
        lastWeather = self.weather();
        lastDimension = self.dimension();
        return event;
    }

    public static TimePhase phaseOf(String timeOfDay) {
        if (timeOfDay == null) return null;
        return switch (timeOfDay) {
            case "morning" -> TimePhase.MORNING;
            case "afternoon" -> TimePhase.AFTERNOON;
            case "evening" -> TimePhase.SUNSET;
            case "night" -> TimePhase.NIGHT;
            default -> null;
        };
    }

    private static boolean known(String s) {
        return s != null && !s.isBlank()
                && !s.equalsIgnoreCase("unknown") && !s.equalsIgnoreCase("unavailable");
    }
}
