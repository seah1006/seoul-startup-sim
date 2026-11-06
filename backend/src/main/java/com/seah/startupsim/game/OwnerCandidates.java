package com.seah.startupsim.game;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import static com.seah.startupsim.game.GameTypes.Background;
import static com.seah.startupsim.game.GameTypes.Candidate;

/** 배경별 분포를 변형하되 모든 후보의 능력치 합을 240으로 유지한다. */
@Component
public class OwnerCandidates {
    public static final List<String> STATS = List.of("cooking","service","management","marketing","stamina","network");
    public List<Candidate> generate(Background background, long seed) {
        int[] template = switch (background) {
            case OFFICE_WORKER -> new int[]{30,35,55,50,35,35};
            case CULINARY -> new int[]{65,35,35,30,45,30};
            case PART_TIMER -> new int[]{40,55,30,30,60,25};
            case LOCAL -> new int[]{30,40,35,35,40,60};
        };
        var random = new SplittableRandom(seed ^ background.ordinal());
        List<Candidate> result = new ArrayList<>();
        for (int c=1;c<=3;c++) {
            int[] values = template.clone();
            for (int i=0;i<24;i++) {
                int a=random.nextInt(6), b=random.nextInt(6);
                if(values[a]>10 && values[b]<90) { values[a]--; values[b]++; }
            }
            Map<String,Integer> stats = new LinkedHashMap<>();
            for(int i=0;i<6;i++) stats.put(STATS.get(i),values[i]);
            result.add(new Candidate("c"+c,stats,240));
        }
        return result;
    }
}
