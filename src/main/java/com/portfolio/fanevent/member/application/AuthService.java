package com.portfolio.fanevent.member.application;

import com.portfolio.fanevent.member.domain.Member;
import com.portfolio.fanevent.member.infrastructure.MemberRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public AuthService(
            MemberRepository memberRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService
    ) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional
    public MemberProfile signup(String email, String rawPassword, String name) {
        String normalizedEmail = normalizeEmail(email);
        if (memberRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException();
        }

        try {
            Member member = Member.register(
                    normalizedEmail,
                    passwordEncoder.encode(rawPassword),
                    name.trim());
            return MemberProfile.from(memberRepository.saveAndFlush(member));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateEmailException();
        }
    }

    @Transactional(readOnly = true)
    public AuthToken login(String email, String rawPassword) {
        Member member = memberRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(rawPassword, member.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return jwtTokenService.issue(member);
    }

    @Transactional(readOnly = true)
    public MemberProfile getProfile(Long memberId) {
        return memberRepository.findById(memberId)
                .map(MemberProfile::from)
                .orElseThrow(() -> new EntityNotFoundException("회원을 찾을 수 없습니다."));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
