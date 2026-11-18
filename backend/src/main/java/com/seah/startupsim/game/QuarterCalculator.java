package com.seah.startupsim.game;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import com.seah.startupsim.game.GameTypes.Decision;
import com.seah.startupsim.game.QuarterOutcome.Headline;
import com.seah.startupsim.game.QuarterOutcome.AreaRank;
import com.seah.startupsim.game.QuarterOutcome.AreaNews;
import com.seah.startupsim.game.GameTypes.Costs;
import com.seah.startupsim.game.GameTypes.StatChange;

/** DB와 무관하게 고정된 입력과 턴별 난수만으로 분기 결산을 계산한다. */
@Component
public class QuarterCalculator {
    public record Input(long seed, int turn, String quarter, long cash, Long marketSales,
            long districtPerStore, int stores, int rivals, double areaM2, long openings, long closings,
            Map<String,Integer> stats, List<String> focus, Decision decision, Map<String,Double> settings,
            String districtCode) {}
    /** 턴별 시드만 사용하므로 재시도나 다른 게임 진행이 매출 난수에 영향을 주지 않는다. */
    public QuarterOutcome calculate(Input input) {
        Decision decision=input.decision();
        Map<String,Double> settings=input.settings();
        long market=input.marketSales()==null ? Math.max(0,input.districtPerStore())*Math.max(0,input.stores()) : Math.max(0,input.marketSales());
        double price=switch(decision.priceLevel()) { case LOW->0.8; case NORMAL->1; case HIGH->1.25; };
        long ticket=Math.round(settings.getOrDefault("ticket",12000d)*price);
        double appeal=settings.getOrDefault("weight.cooking",.25)*input.stats().get("cooking")/40d
                +settings.getOrDefault("weight.service",.2)*input.stats().get("service")/40d
                +settings.getOrDefault("weight.marketing",.15)*(input.stats().get("marketing")/40d+Math.log1p(decision.marketingBudget()/1000000d))
                +settings.getOrDefault("weight.hours",.2)*decision.openHours()/10d+settings.getOrDefault("weight.staff",.2)*(decision.staff()+1)/3d;
        double expectedNearby=Math.max(1,Math.max(0,input.stores())*Math.min(1,Math.PI*300*300/Math.max(1,input.areaM2())));
        double density=1/(1+Math.max(0,input.rivals()/expectedNearby-1)*.15);
        double random=new SplittableRandom(input.seed() ^ ((long)input.turn()*0x9E3779B97F4A7C15L)).nextDouble(.9,1.1);
        long capacity=(long)((decision.staff()+1)*decision.openHours()*90*settings.getOrDefault("capacityPerHour",8d));
        long visitors=Math.min(capacity,Math.max(0,(long)(market/(double)(Math.max(0,input.stores())+1)*appeal*density*random/price/ticket)));
        long revenue=Math.multiplyExact(visitors,ticket);
        long rent=settings.getOrDefault("rent."+input.districtCode(),settings.getOrDefault("rent.default",9000000d)).longValue();
        long ingredients=Math.round(revenue*settings.getOrDefault("cost.FOOD",.35)*(1-(input.stats().get("management")-40)*.002));
        long labor=Math.round(decision.staff()*settings.getOrDefault("labor",6000000d));
        long total=Math.addExact(Math.addExact(rent,ingredients),Math.addExact(labor,decision.marketingBudget()));
        long profit=revenue-total;
        List<StatChange> changes=new ArrayList<>();
        String activity=switch(decision.ownerActivity()) { case MENU_DEV->"cooking"; case REGULARS->"service"; case STUDY->"management"; case REST->"stamina"; };
        for(String stat:OwnerCandidates.STATS) {
            int before=input.stats().get(stat), growth=(stat.equals(activity)?2:0)+(input.focus().contains(stat)?1:0)+(stat.equals("management")?1:0);
            if(growth>0) changes.add(new StatChange(stat,before,Math.min(100,before+growth)));
        }
        return new QuarterOutcome(input.turn(),input.quarter(),new Headline(profit, "이번 분기 순이익"),revenue,visitors,ticket,
                new Costs(rent,ingredients,labor,decision.marketingBudget(),total),profit,Math.addExact(input.cash(),profit),
                new AreaRank(Math.max(1,input.stores()+1), Math.max(1,Math.min(input.stores()+1,(int)Math.ceil((input.stores()+1)/Math.max(.1,appeal))))),
                changes,new AreaNews(input.closings(), input.openings(), "서울시 상권분석서비스 "+input.quarter()),null,List.of(),
                input.marketSales()==null?List.of("이 상권의 매출은 자치구 점포당 평균으로 추정한 값입니다"):List.of());
    }
}
