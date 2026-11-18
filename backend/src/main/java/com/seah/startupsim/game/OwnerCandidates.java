package com.seah.startupsim.game;

import com.seah.startupsim.game.GameTypes.Background;
import com.seah.startupsim.game.GameTypes.Candidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.springframework.stereotype.Component;

/** 배경별 분포를 변형하되 시드가 같으면 후보와 능력치 합 240을 재현한다. */
@Component
public class OwnerCandidates {
    public static final List<String> STATS = List.of(
            "cooking", "service", "management", "marketing", "stamina", "network");

    public List<Candidate> generate(Background background, long seed) {
        int[] template = switch (background) {
            case OFFICE_WORKER -> new int[]{30, 35, 55, 50, 35, 35};
            case CULINARY -> new int[]{65, 35, 35, 30, 45, 30};
            case PART_TIMER -> new int[]{40, 55, 30, 30, 60, 25};
            case LOCAL -> new int[]{30, 40, 35, 35, 40, 60};
        };
        var random = new SplittableRandom(seed ^ background.ordinal());
        List<Candidate> candidates = new ArrayList<>();
        for (int candidateNumber = 1; candidateNumber <= 3; candidateNumber++) {
            int[] values = template.clone();
            for (int adjustment = 0; adjustment < 24; adjustment++) {
                int donor = random.nextInt(6);
                int recipient = random.nextInt(6);
                if (values[donor] > 10 && values[recipient] < 90) {
                    values[donor]--;
                    values[recipient]++;
                }
            }
            Map<String, Integer> stats = new LinkedHashMap<>();
            for (int index = 0; index < 6; index++) {
                stats.put(STATS.get(index), values[index]);
            }
            candidates.add(new Candidate("c" + candidateNumber, stats, 240));
        }
        return candidates;
    }
}
