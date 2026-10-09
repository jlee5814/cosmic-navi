package server.bots.llm;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.bots.BotEntry;
import server.bots.BotOwnershipService;

public enum SenderRelation {
    OWNER, PARTY, STRANGER;

    public static SenderRelation resolve(BotEntry entry, Character sender) {
        if (entry == null || entry.getBot() == null || sender == null) {
            return STRANGER;
        }
        Character owner = entry.getOwner();
        if (owner != null && owner.getId() == sender.getId()) {
            return OWNER;
        }
        // Self-owned roster bots are their own owner; the player registered to them is still the owner.
        if (isRegisteredOwner(entry.getBot().getId(), sender.getId())) {
            return OWNER;
        }
        Party party = entry.getBot().getParty();
        if (party != null) {
            for (PartyCharacter member : party.getMembers()) {
                if (member.getId() == sender.getId()) {
                    return PARTY;
                }
            }
        }
        return STRANGER;
    }

    private static boolean isRegisteredOwner(int botCharId, int senderCharId) {
        try {
            Integer ownerId = BotOwnershipService.getInstance().getRegisteredOwnerId(botCharId);
            return ownerId != null && ownerId == senderCharId;
        } catch (RuntimeException e) {
            return false; // no database (tests, shutdown): fall back to the live owner and party only
        }
    }
}
