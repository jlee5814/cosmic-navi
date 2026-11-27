package server.bots;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotFourthJobSkillsTest {

    private static final Pattern JOB = Pattern.compile("getJobId\\(\\) == (\\d+)");
    private static final Pattern TEACH = Pattern.compile("teachSkill\\((\\d+), 0, 10, -1\\)");

    @Test
    void tableMatchesTheLeafreInstructorScripts() throws IOException {
        Map<Integer, List<Integer>> fromScripts = new TreeMap<>();
        for (int npc : new int[]{2081100, 2081200, 2081300, 2081400, 2081500}) {
            Integer job = null;
            for (String line : Files.readAllLines(Path.of("scripts/npc/" + npc + ".js"))) {
                Matcher j = JOB.matcher(line);
                if (j.find()) job = Integer.parseInt(j.group(1));
                Matcher t = TEACH.matcher(line);
                if (t.find() && job != null) {
                    List<Integer> ids = fromScripts.computeIfAbsent(job, k -> new ArrayList<>());
                    int id = Integer.parseInt(t.group(1));
                    if (!ids.contains(id)) ids.add(id);
                }
            }
        }
        assertEquals(fromScripts, new TreeMap<>(BotFourthJobSkills.BY_JOB));
    }

    @Test
    void bishopGetsGenesisUnlockedAndKeepsHigherMasterLevels() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.BISHOP);
        Skill genesis = skill(2321008);
        Skill bigBang = skill(2321001);
        when(bot.getMasterLevel(any(Skill.class))).thenReturn(0);
        when(bot.getMasterLevel(bigBang)).thenReturn(30);
        when(bot.getSkillLevel(any(Skill.class))).thenReturn((byte) 0);

        try (MockedStatic<SkillFactory> factory = mockStatic(SkillFactory.class)) {
            factory.when(() -> SkillFactory.getSkill(anyInt())).thenAnswer(inv -> {
                int id = inv.getArgument(0);
                return id == 2321008 ? genesis : id == 2321001 ? bigBang : skill(id);
            });
            BotFourthJobSkills.grant(bot);
        }

        verify(bot).changeSkillLevel(genesis, (byte) 0, 10, -1L);
        verify(bot, never()).changeSkillLevel(eq(bigBang), anyByte(), anyInt(), anyLong());
    }

    @Test
    void thirdJobBotGetsNothing() {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(Job.PRIEST);
        BotFourthJobSkills.grant(bot);
        verify(bot, never()).changeSkillLevel(any(Skill.class), anyByte(), anyInt(), anyLong());
    }

    private static Skill skill(int id) {
        Skill skill = mock(Skill.class);
        when(skill.getId()).thenReturn(id);
        return skill;
    }
}
