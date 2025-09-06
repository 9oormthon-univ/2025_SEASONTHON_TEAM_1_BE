package com.goormthonuniv.cleannews.search;

import java.util.List;

public interface SearchAdapter {
    String name(); // "bing", "google_cse", "naver"
    List<SearchResult> search(String query, int limit);
}