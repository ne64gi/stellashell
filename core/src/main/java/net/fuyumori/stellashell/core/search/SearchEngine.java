package net.fuyumori.stellashell.core.search;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;

/** Immutable search destination. Building a URL never performs a search. */
public final class SearchEngine {
    public static final SearchEngine GOOGLE=new SearchEngine("Google","https://www.google.com/search?q={query}");
    public static final SearchEngine DUCKDUCKGO=new SearchEngine("DuckDuckGo","https://duckduckgo.com/?q={query}");
    public static final SearchEngine BING=new SearchEngine("Bing","https://www.bing.com/search?q={query}");
    public final String name,template;
    private final String marker;

    public SearchEngine(String name,String template){
        this.name=trim(name);this.template=trim(template);
        if(!validName(this.name))throw new IllegalArgumentException("Invalid search engine name");
        marker=validateTemplate(this.template);
    }
    public static boolean validName(String name){
        String value=trim(name);if(value.isEmpty()||value.length()>60)return false;
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return false;
        return true;
    }
    private static String validateTemplate(String template){
        if(template.isEmpty()||template.length()>2048)throw new IllegalArgumentException("Invalid search URL");
        String marker=template.contains("{query}")?"{query}":"%s";
        int at=template.indexOf(marker);
        if(at<0||template.indexOf(marker,at+marker.length())>=0
                ||template.contains(marker.equals("%s")?"{query}":"%s"))
            throw new IllegalArgumentException("Search URL needs one query placeholder");
        try{
            URI uri=new URI(template.replace(marker,""));
            int authorityEnd=template.indexOf("://")+3;
            while(authorityEnd<template.length()&&"/?#".indexOf(template.charAt(authorityEnd))<0)authorityEnd++;
            int fragment=template.indexOf('#');
            if(!("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))
                    ||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getPort()>65535
                    ||at<authorityEnd||fragment>=0&&at>fragment)
                throw new IllegalArgumentException("Invalid search destination");
        }catch(URISyntaxException error){throw new IllegalArgumentException("Invalid search URL",error);}
        return marker;
    }
    public String url(String query){
        String value=trim(query);if(value.isEmpty())throw new IllegalArgumentException("Empty search");
        try{return template.replace(marker,URLEncoder.encode(value,"UTF-8").replace("+","%20"));}
        catch(UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
    }
    public static String trim(String value){
        if(value==null)return "";int first=0,last=value.length();
        while(first<last){int c=value.codePointAt(first);if(!space(c))break;first+=Character.charCount(c);}
        while(last>first){int c=value.codePointBefore(last);if(!space(c))break;last-=Character.charCount(c);}
        return value.substring(first,last);
    }
    private static boolean space(int c){return Character.isWhitespace(c)||Character.isSpaceChar(c);}
}
