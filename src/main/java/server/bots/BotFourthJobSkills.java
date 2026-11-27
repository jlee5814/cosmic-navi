package server.bots;

import client.Character;
import client.Skill;
import client.SkillFactory;

import java.util.List;
import java.util.Map;

/**
 * The 4th job skills the Leafre instructors (scripts/npc/2081100.js to 2081500.js) unlock at master level 10.
 * A player gets them by talking to the instructor (cm.teachSkill(id, 0, 10, -1)); a bot advances through
 * changeJob, so it gets the same unlock here. Without it a 4th job bot can't put SP into the skill its build
 * opens with (Genesis, Blizzard), whose master level stays 0. BotFourthJobSkillsTest reads the scripts and
 * fails when this table drifts from them.
 */
final class BotFourthJobSkills {
    static final int MASTER_LEVEL = 10;

    static final Map<Integer, List<Integer>> BY_JOB = Map.ofEntries(
            Map.entry(112, List.of(1121001, 1120004, 1121008, 1121010, 1120005, 1121002)),
            Map.entry(122, List.of(1221001, 1220005, 1221009, 1221002, 1221003, 1221004)),
            Map.entry(132, List.of(1321001, 1320005, 1321007, 1321002, 1320008, 1320009)),
            Map.entry(212, List.of(2121001, 2121002, 2121006, 2121007, 2121005)),
            Map.entry(222, List.of(2221001, 2221002, 2221006, 2221007, 2221005, 2221003)),
            Map.entry(232, List.of(2321001, 2321002, 2321005, 2321008, 2321006)),
            Map.entry(312, List.of(3121002, 3120005, 3121007, 3121008, 3121006, 3121004)),
            Map.entry(322, List.of(3221002, 3220004, 3221006, 3221007, 3221005, 3221001)),
            Map.entry(412, List.of(4120002, 4120005, 4121006, 4121008, 4121004)),
            Map.entry(422, List.of(4220002, 4220005, 4221007, 4221004, 4221001)),
            Map.entry(512, List.of(5121001, 5121002, 5121007, 5121009, 5121003, 5121004, 5121005, 5121010)),
            Map.entry(522, List.of(5220001, 5220002, 5221004, 5220011, 5221006, 5221007, 5221008, 5221009, 5221003))
    );

    private BotFourthJobSkills() {}

    /** Unlocks the instructor's skills for the bot's current job; a skill already at master 10 or above is left alone. */
    static void grant(Character bot) {
        List<Integer> skillIds = BY_JOB.get(bot.getJob().getId());
        if (skillIds == null) return;
        for (int skillId : skillIds) {
            Skill skill = SkillFactory.getSkill(skillId);
            if (skill == null || bot.getMasterLevel(skill) >= MASTER_LEVEL) continue;
            bot.changeSkillLevel(skill, bot.getSkillLevel(skill), MASTER_LEVEL, -1);
        }
    }
}
