package com.portfolio.fanevent.member.application;

import com.portfolio.fanevent.member.domain.Member;
import com.portfolio.fanevent.member.domain.MemberRole;

public record MemberProfile(
        Long id,
        String email,
        String name,
        MemberRole role
) {

    public static MemberProfile from(Member member) {
        return new MemberProfile(
                member.getId(),
                member.getEmail(),
                member.getName(),
                member.getRole());
    }
}
