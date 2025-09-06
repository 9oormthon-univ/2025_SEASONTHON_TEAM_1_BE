package com.goormthonuniv.cleannews.verify;

import java.time.LocalDate;
import java.util.*;

public class ExtractedFacts {

    private Set<LocalDate> dates;
    private String venue;

    private String eventName;
    private String locationVenue;
    private String locationCity;
    private List<String> hashtags = new ArrayList<>();
    private List<String> orgHandles = new ArrayList<>();
    private List<String> brandNames = new ArrayList<>();

    public ExtractedFacts(Set<LocalDate> dates, String venue) {
        this.dates = dates != null ? dates : new HashSet<>();
        this.venue = venue;
    }

    public ExtractedFacts() {
        this.dates = new HashSet<>();
    }

    // === 팩토리 메서드 추가 ===
    public static ExtractedFacts from(String normalized) {
        return TextKoreanUtils.parseFacts(normalized);
    }

    // --- getters ---
    public Set<LocalDate> getDates() { return dates; }
    public String getVenue() { return venue; }

    public String getEventName() { return eventName; }
    public String getLocationVenue() { return locationVenue; }
    public String getLocationCity() { return locationCity; }
    public List<String> getHashtags() { return hashtags; }
    public List<String> getOrgHandles() { return orgHandles; }
    public List<String> getBrandNames() { return brandNames; }

    // --- setters ---
    public void setEventName(String eventName) { this.eventName = eventName; }
    public void setLocationVenue(String locationVenue) { this.locationVenue = locationVenue; }
    public void setLocationCity(String locationCity) { this.locationCity = locationCity; }
    public void setHashtags(List<String> hashtags) { this.hashtags = hashtags; }
    public void setOrgHandles(List<String> orgHandles) { this.orgHandles = orgHandles; }
    public void setBrandNames(List<String> brandNames) { this.brandNames = brandNames; }

    // --- 유틸 ---
    public double matchScore(ExtractedFacts cand) {
        if (cand == null) return 0.0;
        double score = 0.0;

        if (dates != null && !dates.isEmpty() && cand.dates != null && !cand.dates.isEmpty()) {
            Set<LocalDate> inter = new HashSet<>(dates);
            inter.retainAll(cand.dates);
            score += !inter.isEmpty() ? 0.7 : 0.0;
        }
        if (venue != null && cand.venue != null && venue.equalsIgnoreCase(cand.venue)) {
            score += 0.3;
        }
        return Math.min(1.0, score);
    }

    public String factHitExplain(ExtractedFacts cand) {
        if (cand == null) return null;
        boolean dateHit = false;
        if (dates != null && cand.dates != null) {
            Set<LocalDate> inter = new HashSet<>(dates);
            inter.retainAll(cand.dates);
            dateHit = !inter.isEmpty();
        }
        boolean venueHit = (venue != null && cand.venue != null && venue.equalsIgnoreCase(cand.venue));
        if (dateHit || venueHit) {
            return String.format("날짜일치:%s / 장소일치:%s", dateHit ? "Y" : "N", venueHit ? "Y" : "N");
        }
        return null;
    }

    public String getDateText() {
        if (dates == null || dates.isEmpty()) return null;
        return dates.iterator().next().toString();
    }
}