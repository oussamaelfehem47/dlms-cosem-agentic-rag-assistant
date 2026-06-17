package com.company.dlms.infrastructure.security;
 
import com.company.dlms.domain.security.User;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
 
import java.util.List;
 
@Service
public class CustomUserDetailsService implements ReactiveUserDetailsService {
 
    private final UserRepository userRepository;
 
    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
 
    @Override
    public Mono<UserDetails> findByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(this::toUserDetails)
                .switchIfEmpty(userRepository.findByEmail(username).map(this::toUserDetails));
    }
 
    private UserDetails toUserDetails(User user) {
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.username())
                .password(user.passwordHash())
                .authorities(List.of(new SimpleGrantedAuthority(user.role().toAuthority())))
                .build();
    }
}
