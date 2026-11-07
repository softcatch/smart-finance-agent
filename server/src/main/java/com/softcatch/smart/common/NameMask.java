package com.softcatch.smart.common;

// 송금 전에 보이는 이름은 가운데를 가린다(계좌번호만으로 실명을 모으지 못하게). 김영희 → 김*희, 이수 → 이*.
public final class NameMask {

  private NameMask() {}

  public static String mask(String name) {
    int[] chars = name.codePoints().toArray();
    if (chars.length < 2) {
      return name;
    }
    StringBuilder masked = new StringBuilder().appendCodePoint(chars[0]);
    masked.append("*".repeat(Math.max(1, chars.length - 2)));
    if (chars.length > 2) {
      masked.appendCodePoint(chars[chars.length - 1]);
    }
    return masked.toString();
  }
}
