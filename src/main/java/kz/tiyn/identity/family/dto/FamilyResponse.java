package kz.tiyn.identity.family.dto;

import java.util.List;
import java.util.UUID;

public record FamilyResponse(List<Member> members) {

    public record Member(UUID id, String role, String displayName) {}
}
