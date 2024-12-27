package cn.ken.shoes.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtUtil {
    
    private static String secret = "01234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789";  // 从配置文件读取
    private static Long expiration = 86400000L;  // 24小时
    
    public static String generateToken(Long userId) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);
        
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .setIssuedAt(now)
                .setExpiration(expiryDate)
                .signWith(SignatureAlgorithm.HS512, secret)
                .compact();
    }
    
    public static Long getUserIdFromToken(String token) {
        try {
            System.out.println("Parsing token: " + token);
            Claims claims = Jwts.parser()
                    .setSigningKey(secret)
                    .parseClaimsJws(token)
                    .getBody();
            String subject = claims.getSubject();
            System.out.println("Extracted subject: " + subject);
            return Long.parseLong(subject);
        } catch (Exception e) {
            System.out.println("Error parsing token: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
} 