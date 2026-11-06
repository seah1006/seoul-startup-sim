package com.seah.startupsim.game;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import com.seah.startupsim.game.GameTypes.Decision;
import com.seah.startupsim.game.GameTypes.Price;
import com.seah.startupsim.game.GameTypes.Activity;
import com.seah.startupsim.game.GameTypes.Result;
import com.seah.startupsim.game.GameTypes.Costs;
import com.seah.startupsim.game.GameTypes.StatChange;

/** DB와 무관하게 고정된 입력과 턴별 난수만으로 분기 결산을 계산한다. */
@Component
public class QuarterCalculator {
    public record Input(long seed, int turn, String quarter, long cash, Long marketSales,
            long districtPerStore, int stores, int rivals, double areaM2, long openings, long closings,
            Map<String,Integer> stats, List<String> focus, Decision decision, Map<String,Double> settings,
            String districtCode) {}
    public Result calculate(Input i) {
        Decision d=i.decision();
        Map<String,Double> s=i.settings();
        long market=i.marketSales()==null ? Math.max(0,i.districtPerStore())*Math.max(0,i.stores()) : Math.max(0,i.marketSales());
        double price=switch(d.priceLevel()) { case LOW->0.8; case NORMAL->1; case HIGH->1.25; };
        long ticket=Math.round(s.getOrDefault("ticket",12000d)*price);
        double appeal=s.getOrDefault("weight.cooking",.25)*i.stats().get("cooking")/40d
                +s.getOrDefault("weight.service",.2)*i.stats().get("service")/40d
                +s.getOrDefault("weight.marketing",.15)*(i.stats().get("marketing")/40d+Math.log1p(d.marketingBudget()/1000000d))
                +s.getOrDefault("weight.hours",.2)*d.openHours()/10d+s.getOrDefault("weight.staff",.2)*(d.staff()+1)/3d;
        double expectedNearby=Math.max(1,Math.max(0,i.stores())*Math.min(1,Math.PI*300*300/Math.max(1,i.areaM2())));
        double density=1/(1+Math.max(0,i.rivals()/expectedNearby-1)*.15);
        double random=new SplittableRandom(i.seed() ^ ((long)i.turn()*0x9E3779B97F4A7C15L)).nextDouble(.9,1.1);
        long capacity=(long)((d.staff()+1)*d.openHours()*90*s.getOrDefault("capacityPerHour",8d));
        long visitors=Math.min(capacity,Math.max(0,(long)(market/(double)(Math.max(0,i.stores())+1)*appeal*density*random/price/ticket)));
        long revenue=Math.multiplyExact(visitors,ticket);
        long rent=s.getOrDefault("rent."+i.districtCode(),s.getOrDefault("rent.default",9000000d)).longValue();
        long ingredients=Math.round(revenue*s.getOrDefault("cost.FOOD",.35)*(1-(i.stats().get("management")-40)*.002));
        long labor=Math.round(d.staff()*s.getOrDefault("labor",6000000d));
        long total=Math.addExact(Math.addExact(rent,ingredients),Math.addExact(labor,d.marketingBudget()));
        long profit=revenue-total;
        List<StatChange> changes=new ArrayList<>();
        String activity=switch(d.ownerActivity()) { case MENU_DEV->"cooking"; case REGULARS->"service"; case STUDY->"management"; case REST->"stamina"; };
        for(String stat:OwnerCandidates.STATS) {
            int before=i.stats().get(stat), growth=(stat.equals(activity)?2:0)+(i.focus().contains(stat)?1:0)+(stat.equals("management")?1:0);
            if(growth>0) changes.add(new StatChange(stat,before,Math.min(100,before+growth)));
        }
        return new Result(i.turn(),i.quarter(),Map.of("label","이번 분기 순이익","amount",profit),revenue,visitors,ticket,
                new Costs(rent,ingredients,labor,d.marketingBudget(),total),profit,Math.addExact(i.cash(),profit),
                Map.of("rank",Math.max(1,Math.min(i.stores()+1,(int)Math.ceil((i.stores()+1)/Math.max(.1,appeal)))),"of",Math.max(1,i.stores()+1)),
                changes,Map.of("openings",i.openings(),"closings",i.closings(),"source","서울시 상권분석서비스 "+i.quarter()),null,List.of(),
                i.marketSales()==null?List.of("이 상권의 매출은 자치구 점포당 평균으로 추정한 값입니다"):List.of());
    }
}
