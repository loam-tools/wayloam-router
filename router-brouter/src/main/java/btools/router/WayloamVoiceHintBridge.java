package btools.router;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Narrow package-local bridge that exposes BRouter's already-computed voice hints without reflection
 * or leaking upstream implementation classes into the public WAYLOAM Router API.
 */
public final class WayloamVoiceHintBridge {
  private WayloamVoiceHintBridge() {}

  public static final class Hint {
    public final int indexInTrack;
    public final int command;
    public final int exitNumber;
    public final double distanceToNext;
    public final double angle;

    Hint(int indexInTrack, int command, int exitNumber, double distanceToNext, double angle) {
      this.indexInTrack = indexInTrack;
      this.command = command;
      this.exitNumber = exitNumber;
      this.distanceToNext = distanceToNext;
      this.angle = angle;
    }
  }

  public static List<Hint> read(OsmTrack track) {
    if (track == null || track.voiceHints == null || track.voiceHints.list == null || track.voiceHints.list.isEmpty()) {
      return Collections.emptyList();
    }
    List<Hint> result = new ArrayList<>(track.voiceHints.list.size());
    for (VoiceHint hint : track.voiceHints.list) {
      result.add(new Hint(
          hint.indexInTrack,
          hint.cmd,
          hint.getExitNumber(),
          hint.distanceToNext,
          hint.angle
      ));
    }
    return result;
  }
}
