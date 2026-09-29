package com.kratisai.controlplane.repository;

import com.kratisai.controlplane.model.RefreshToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("delete from RefreshToken rt where rt.token = :token")
    long deleteByToken(@Param("token") String token);

    @Modifying
    @Query("delete from RefreshToken rt where rt.user.id = :userId")
    long deleteByUserId(@Param("userId") UUID userId);
}
