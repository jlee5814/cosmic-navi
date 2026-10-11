package server.bots.llm;

import client.Character;
import client.Skill;
import client.SkillFactory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SituationOnDemandTest {

    @Test
    void gearAndSkillSectionsOnlyForQuestionsThatAskForThem() {
        assertTrue(SituationBuilder.asksAboutGear("what are you equipped with"));
        assertTrue(SituationBuilder.asksAboutGear("nice staff, where'd you get it"));
        assertTrue(SituationBuilder.asksAboutSkills("what skill do you use the most"));
        assertTrue(SituationBuilder.asksAboutSkills("did you max lightning"));
        assertFalse(SituationBuilder.asksAboutGear("what level are you"));
        assertFalse(SituationBuilder.asksAboutSkills("wanna go to henesys later"));
    }

    private static Skill skill(int id, int max) {
        Skill s = mock(Skill.class);
        when(s.getId()).thenReturn(id);
        when(s.getMaxLevel()).thenReturn(max);
        return s;
    }

    @Test
    void skillsListHighestLevelsFirstWithTheirMax() {
        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        skills.put(skill(2001004, 20), new Character.SkillEntry((byte) 1, 0, -1));   // Energy Bolt 1/20
        skills.put(skill(2201005, 30), new Character.SkillEntry((byte) 20, 0, -1));  // Thunder Bolt 20/30
        skills.put(skill(2001002, 20), new Character.SkillEntry((byte) 0, 0, -1));   // unlearned, dropped
        Character bot = mock(Character.class);
        when(bot.getSkills()).thenReturn(skills);
        try (MockedStatic<SkillFactory> sf = mockStatic(SkillFactory.class)) {
            sf.when(() -> SkillFactory.getSkillName(anyInt())).thenAnswer(inv -> switch ((int) inv.getArgument(0)) {
                case 2001004 -> "Energy Bolt";
                case 2201005 -> "Thunder Bolt";
                default -> "Magic Guard";
            });
            assertEquals("Thunder Bolt 20/30, Energy Bolt 1/20", SituationBuilder.describeSkills(bot));
        }
    }
}
