package com.valar.basestrategy.service;

import com.valar.basestrategy.entities.Ohlc;
import com.valar.basestrategy.entities.TradeEntity;
import com.valar.basestrategy.state.minute.IndexState;
import com.valar.basestrategy.state.minute.State;
import com.valar.basestrategy.tradeAndDayMetrics.DayMetric;
import com.valar.basestrategy.utils.KeyValues;
import org.ta4j.core.Bar;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class StrategyImpl {
    private int tradeId;
    private float dayMaxProfit, dayMaxProfitPercent;
    public KeyValues kv;
    public boolean dayExited;
    private int unSquaredTrades;
    private final List<TradeEntity> tradeEntities = new ArrayList<>();
    private final State indexState;
    private final List<Map<String, DayMetric>> dayMetricsMapList;
    private double dayAtrPercent, dayAtrPercentage;
    private boolean dayATRConditionSatisfied, candlePeriodBelongsToDay;
    private final Map<Integer, IndexState> indexStateMap;
    private final Map<String, Double> dayAtrMap, dayAtrMapPercentage;
    private int parserAtLastTrade;
    private String lastAtrCheckeAtDate = "";
   private int chk=0;
    // Pivot tracking fields
    private String prevDate = null;
    private List<Ohlc> prevDayBars = new ArrayList<>();
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("dd-MM-yy HH:mm");

    public StrategyImpl(
            boolean candlePeriodBelongsToDay,
            Map<Integer, IndexState> indexStateMap,
            KeyValues kv,
            Map<String, Double> dayAtrMap,
            Map<String, Double> dayAtrMapPercentage,
            State indexState,
            Map<String, DayMetric> dayMetricsMap,
            Map<String, DayMetric> stockDayMetricsMap
    ) {
        this.indexStateMap = indexStateMap;
        this.kv = kv;
        this.indexState = indexState;
        this.dayAtrMap = dayAtrMap;
        this.dayAtrMapPercentage = dayAtrMapPercentage;
        this.dayMetricsMapList = new ArrayList<>(Arrays.asList(dayMetricsMap, stockDayMetricsMap));
        this.candlePeriodBelongsToDay = candlePeriodBelongsToDay;
    }

    public void setUnSquaredTrades(int unSquaredTrades) {
        this.unSquaredTrades = unSquaredTrades;
    }

    public void iterate(int mins) {
        String currDate = indexState.ohlc.date;

        // PIVOT LOGIC
        if (prevDate != null && !prevDate.equals(currDate)) {
            if (!prevDayBars.isEmpty()) {
                float hi = Float.NEGATIVE_INFINITY, lo = Float.POSITIVE_INFINITY, cls = 0;
                for (Ohlc bar : prevDayBars) {
                    hi = Math.max(hi, bar.high);
                    lo = Math.min(lo, bar.low);
                    cls = bar.close;
                }
                indexState.computePivots(hi, lo, cls);
                indexState.pivotsInitialized = true;
            } else {
                indexState.pivotsInitialized = false;
            }
            prevDayBars.clear();
        }
        prevDate = currDate;
        prevDayBars.add(new Ohlc(indexState.ohlc));

        if ((mins >= kv.startTime || candlePeriodBelongsToDay)
                && !lastAtrCheckeAtDate.equals(indexState.ohlc.date)) {
            lastAtrCheckeAtDate = indexState.ohlc.date;
            if (!dayAtrMap.containsKey(indexState.ohlc.date)) {
                if (!kv.positional) dayExited = true;
                return;
            }
            dayAtrPercent = dayAtrMap.get(indexState.ohlc.date);
            dayAtrPercentage = dayAtrMapPercentage.get(indexState.ohlc.date);
            dayATRConditionSatisfied = dayAtrPercent >= 0
                    && (dayAtrPercent >= kv.atrFrom && dayAtrPercent <= kv.atrTo);
            if (!dayATRConditionSatisfied && !kv.positional) {
                dayExited = true;
                return;
            }
        }

        if (mins >= kv.startTime || candlePeriodBelongsToDay) {
            checkForExitsInEnteredTrades();
            /*System.out.println("mins: " + indexState.ohlc.mins +
                    ", startTime: " + kv.startTime +
                    ", cutOffTime: " + kv.cutOffTime +
                    ", unSquaredTrades: " + unSquaredTrades +
                    ", maxOverlap: " + kv.maxOverlap +
                    ", parser: " + indexState.parser +
                    ", parserAtLastTrade: " + parserAtLastTrade +
                    ", tradeGap: " + kv.tradeGap);

            System.out.println("mins >= startTime: " + (indexState.ohlc.mins >= kv.startTime));
            System.out.println("mins <= cutOffTime: " + (indexState.ohlc.mins <= kv.cutOffTime));
            System.out.println("unSquaredTrades < maxOverlap: " + (unSquaredTrades < kv.maxOverlap));
            System.out.println("parser - parserAtLastTrade >= tradeGap: " + (indexState.parser - parserAtLastTrade >= kv.tradeGap));*/

            boolean entryConditionSatisfied = indexState.ohlc.mins >= kv.startTime
                    && indexState.ohlc.mins <= kv.cutOffTime
                    && unSquaredTrades < kv.maxOverlap
                    && indexState.parser - parserAtLastTrade >= kv.tradeGap;
                   // && chk<100000;
            //entryConditionSatisfied=chk<10000;
            chk++;
            // ENTRY
            if (dayATRConditionSatisfied && entryConditionSatisfied && indexState.pivotsInitialized) {
                if (mins >= kv.startTime && kv.rsiPeriod != 0 && kv.positional) {
                    runOptionalLogic(entryConditionSatisfied);
                }
            }
        }
    }

    private void runOptionalLogic(boolean entryConditionSatisfied) {
        Ohlc bar = indexState.ohlc;
        int curIdx = indexState.series.getEndIndex();
        indexState.loadIndicators(kv.emaPeriod, kv.rsiPeriod);

        double emaVal = indexState.getEmaVal(kv.emaPeriod);
        double rsiVal = indexState.getRsiVal(kv.rsiPeriod);

        // last 10-candle high/low
        float high10 = Float.NEGATIVE_INFINITY, low10 = Float.POSITIVE_INFINITY;
        for (int i = curIdx - 10; i < curIdx; ++i) {
            if (i < 0) continue;
            Bar o = indexState.series.getBar(i);
            high10 = Math.max(high10, o.getHighPrice().floatValue());
            low10 = Math.min(low10, o.getLowPrice().floatValue());
        }

        // ENTRY: LONG
        if (kv.usePivots) {
            if (rsiVal > 60 && bar.close > emaVal && bar.high > high10) {
                kv.tradeType = "l";
                TradeEntity trade = new TradeEntity(tradeId, 0, 0, kv, (IndexState) indexState, indexStateMap);
                trade.entryEma = emaVal;
                trade.entryRsi = rsiVal;
                trade.entryPivot = (indexState.pivotsInitialized ? ((float)indexState.pp) : Float.NaN);
                trade.setStopLoss(low10);
                trade.setTarget(indexState.nearestAbove(bar.close));
                tradeEntities.add(trade);
                tradeId++;
                parserAtLastTrade = indexState.parser;
            }
            // ENTRY: SHORT
            if (rsiVal < 40 && bar.close < emaVal && bar.low < low10) {
                kv.tradeType = "s";
                TradeEntity trade = new TradeEntity(tradeId, 0, 0, kv, (IndexState) indexState, indexStateMap);
                trade.entryEma = emaVal;
                trade.entryRsi = rsiVal;
                trade.entryPivot = (indexState.pivotsInitialized ? ((float)indexState.pp) : Float.NaN);
                trade.setStopLoss(high10);
                trade.setTarget(indexState.nearestBelow(bar.close));
                tradeEntities.add(trade);
                tradeId++;
                parserAtLastTrade = indexState.parser;
            }
        }
    }


    public void checkForExitsInEnteredTrades() {
        Ohlc bar = indexState.ohlc;
        LocalDateTime barDateTime = LocalDateTime.parse(bar.date + " " + bar.time, DTF);

        float totalProfitPercent = 0, totalProfit = 0;
        unSquaredTrades = 0;
        for (TradeEntity tradeEntity : tradeEntities) {
            if (tradeEntity.tradeSquared) continue;

            char lOrS = tradeEntity.tradeAttribs.get(0).lOrS;
            boolean forceExit = (barDateTime.getDayOfWeek() == DayOfWeek.THURSDAY && bar.mins >= (15 * 60 + 15));
            boolean hitSL = false, hitTarget = false;

            if (lOrS == 'l') {
                hitSL = (bar.low <= tradeEntity.stopLoss);
                hitTarget = (bar.high >= tradeEntity.target);
            } else if (lOrS == 's') {
                hitSL = (bar.high >= tradeEntity.stopLoss);
                hitTarget = (bar.low <= tradeEntity.target);
            }

            if (hitSL || hitTarget || forceExit) {
                tradeEntity.forceExit();
                onTradeExit(bar.date, tradeEntity);
            }

            if (!tradeEntity.tradeSquared) unSquaredTrades++;
            totalProfitPercent += tradeEntity.getTotalProfitPercent();
            totalProfit += tradeEntity.getTotalProfit();
        }
        dayMaxProfit = Float.max(dayMaxProfit, totalProfit);
        dayMaxProfitPercent = Float.max(dayMaxProfitPercent, totalProfitPercent);
    }

    public void onTradeExit(String date, TradeEntity tradeEntity) {
        try {
            dayMetricsMapList.parallelStream().forEach(dayMetricsMap -> {
                DayMetric dayMetric;
                if (dayMetricsMap.containsKey(date)) dayMetric = dayMetricsMap.get(date);
                else {
                    dayMetric = new DayMetric(date, kv.costPercent, tradeEntity.indexCloseAtEntry, kv.sno);
                    dayMetricsMap.put(date, dayMetric);
                }
                dayMetric.updateMetric(tradeEntity.overAllTradeMetric, dayMaxProfit, dayMaxProfitPercent);
            });
        } catch (Exception e) {
            System.out.println(dayMetricsMapList);
            e.printStackTrace();
            System.out.println(dayMetricsMapList);
            System.exit(1);
        }
    }
}