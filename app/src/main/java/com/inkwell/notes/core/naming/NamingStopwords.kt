package com.inkwell.notes.core.naming

/**
 * Words that never make a topic on their own: function words (English plus the most common
 * ones of other Latin-script languages, so foreign notes do not end up named "Der Die"),
 * note-taking filler ("notes", "meeting", "agenda", "action items"...), calendar words and
 * vague verbs/adverbs. Keys are lowercase with straight apostrophes.
 */
internal object NamingStopwords {
    /** Connectives allowed in the middle of a three-word phrase ("Bank of England"). */
    val CONNECTORS: Set<String> = setOf("of", "and", "for", "&", "in", "on", "de", "du", "des", "der", "la", "di", "del")

    private val ENGLISH = """
        a about above across after afterwards again against all almost alone along already also although always am
        among amongst an and another any anybody anyhow anyone anything anyway anywhere are aren't around as at back
        be became because become becomes becoming been before beforehand behind being below beside besides between
        beyond both but by can can't cannot could couldn't did didn't do does doesn't doing don't done down during
        each either else elsewhere enough even ever every everybody everyone everything everywhere except few for
        former formerly from further had hadn't has hasn't have haven't having he he'd he'll he's hence her here
        here's hereafter hereby herein hers herself him himself his how how's however i i'd i'll i'm i've ie if in
        indeed into is isn't it it's its itself just last latter latterly least less let let's lets like likely
        many may maybe me meanwhile might mine more moreover most mostly much must mustn't my myself namely neither
        never nevertheless no nobody none noone nor not nothing now nowhere of off often on once only onto or other
        others otherwise our ours ourselves out over overall own per perhaps please quite rather re really same
        several shall shan't she she'd she'll she's should shouldn't since so some somebody somehow someone
        something sometime sometimes somewhere still such than that that's the their theirs them themselves then
        thence there there's thereafter thereby therefore therein thereupon these they they'd they'll they're
        they've this those though through throughout thru thus to together too toward towards under unless until
        up upon us very via was wasn't we we'd we'll we're we've were weren't what what's whatever when when's
        whence whenever where where's whereas whereby wherein whereupon wherever whether which while whither who
        who's whoever whole whom whose why why's will with within without won't would wouldn't yet you you'd
        you'll you're you've your yours yourself yourselves
        able across actually almost already basically certainly clearly definitely especially essentially
        exactly fairly generally hopefully kind largely literally mainly nearly obviously probably quickly
        roughly simply slightly sort somewhat soon suddenly surely truly usually
        add added adding ask asked asking become begin began call called calls came come comes coming consider
        considered could did does doing done get gets getting give given gives giving go goes going gone got
        gotten have help helps keep keeps kept know known knows let look looked looking looks made make makes
        making mean means meant need needed needs put puts said say saying says see seeing seem seemed seems seen
        set sets show showed shown shows take taken takes taking tell telling tells think thinking thought try
        trying use used uses using want wanted wants went work works
        able bad best better big good great high large little long low lot lots new nice old okay ok right small
        sure true wrong yes yeah
        one two three four five six seven eight nine ten eleven twelve first second third fourth fifth next
        previous last final half once twice
        way ways thing things stuff part parts bit bits piece kind kinds type types case cases lot fact facts
        reason reasons place places number numbers amount group
        time times day days week weeks weekly month months monthly year years yearly today tomorrow yesterday
        tonight morning afternoon evening night hour hours minute minutes am pm
        monday tuesday wednesday thursday friday saturday sunday mon tue tues wed thu thur thurs fri sat sun
        january february march april may june july august september october november december
        jan feb mar apr jun jul aug sep sept oct nov dec
        etc eg e.g i.e vs versus via fyi asap btw tbd tbc n/a na
    """.trimIndent()

    private val NOTE_FILLER = """
        note notes noted notebook notebooks page pages slide slides chapter chapters section sections handout
        meeting meetings lecture lectures class classes session sessions seminar workshop tutorial call calls sync
        standup agenda minutes recap summary summaries overview intro introduction conclusion conclusions
        discussion discussions discuss discussed discussing talk talked talking said mentioned
        action actions item items todo todos to-do task tasks follow followup follow-up steps step plan plans
        question questions answer answers idea ideas thought thoughts point points key important importance
        remember reminder reminders review reviewed reviewing example examples misc miscellaneous general various
        random draft drafts untitled update updates status team everyone people attendee attendees present
        absent apologies regards thanks thank hello hi dear cc re fw fwd ok done tbd pending wip
        list lists table figure fig chart diagram see also below above following
        page's lecture's meeting's
    """.trimIndent()

    private val OTHER_LANGUAGES = """
        der die das den dem des ein eine einer eines einem einen und oder aber nicht ist sind war waren wird
        werden wurde mit von zu zum zur auf aus bei nach vor für fur über uber unter durch als auch noch nur
        schon sehr wie was wer wo hier dort dann wenn weil dass ich du er sie es wir ihr man sich mein dein sein
        le la les un une des du de et ou mais pas est sont était être avec pour par sur dans sans sous chez
        que qui quoi dont où au aux ce cet cette ces il elle ils elles nous vous je tu mon ton son ses leur leurs
        plus moins très aussi
        el los las una unos unas y o pero no es son era fue ser estar está están con por para sin sobre entre
        que quien como cuando donde del al lo su sus mi mis tu muy más mas también tambien
        il lo gli uno una e o ma non è sono era con per tra fra su da di del della dei delle nel nella che chi
        come quando dove anche molto più piu
        os as um uma uns umas e ou mas não nao é são sao era com por para sem sobre entre que quem como quando
        onde do da dos das no na nos nas também muito mais
        het een en of maar niet is zijn was waren met voor van op aan in uit bij naar door als ook nog wel
        zeer dat die dit deze wie wat waar hoe
    """.trimIndent()

    val ALL: Set<String> = (ENGLISH + "\n" + NOTE_FILLER + "\n" + OTHER_LANGUAGES)
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .toHashSet()

    fun isStop(key: String): Boolean = key in ALL
}
