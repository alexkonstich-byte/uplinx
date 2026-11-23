//! Country names for flag codes (servers grouped by country).

static NAMES: &[(&str, &str, &str)] = &[
    ("AE", "UAE", "ОАЭ"), ("AL", "Albania", "Албания"), ("AM", "Armenia", "Армения"), ("AR", "Argentina", "Аргентина"),
    ("AT", "Austria", "Австрия"), ("AU", "Australia", "Австралия"), ("AZ", "Azerbaijan", "Азербайджан"), ("BA", "Bosnia", "Босния"),
    ("BE", "Belgium", "Бельгия"), ("BG", "Bulgaria", "Болгария"), ("BR", "Brazil", "Бразилия"), ("BY", "Belarus", "Беларусь"),
    ("CA", "Canada", "Канада"), ("CH", "Switzerland", "Швейцария"), ("CL", "Chile", "Чили"), ("CN", "China", "Китай"),
    ("CO", "Colombia", "Колумбия"), ("CY", "Cyprus", "Кипр"), ("CZ", "Czechia", "Чехия"), ("DE", "Germany", "Германия"),
    ("DK", "Denmark", "Дания"), ("EE", "Estonia", "Эстония"), ("EG", "Egypt", "Египет"), ("ES", "Spain", "Испания"),
    ("FI", "Finland", "Финляндия"), ("FR", "France", "Франция"), ("GB", "United Kingdom", "Великобритания"), ("GE", "Georgia", "Грузия"),
    ("GR", "Greece", "Греция"), ("HK", "Hong Kong", "Гонконг"), ("HR", "Croatia", "Хорватия"), ("HU", "Hungary", "Венгрия"),
    ("ID", "Indonesia", "Индонезия"), ("IE", "Ireland", "Ирландия"), ("IL", "Israel", "Израиль"), ("IN", "India", "Индия"),
    ("IR", "Iran", "Иран"), ("IS", "Iceland", "Исландия"), ("IT", "Italy", "Италия"), ("JP", "Japan", "Япония"),
    ("KG", "Kyrgyzstan", "Киргизия"), ("KR", "South Korea", "Южная Корея"), ("KZ", "Kazakhstan", "Казахстан"), ("LT", "Lithuania", "Литва"),
    ("LU", "Luxembourg", "Люксембург"), ("LV", "Latvia", "Латвия"), ("MD", "Moldova", "Молдова"), ("ME", "Montenegro", "Черногория"),
    ("MK", "North Macedonia", "Северная Македония"), ("MN", "Mongolia", "Монголия"), ("MX", "Mexico", "Мексика"), ("MY", "Malaysia", "Малайзия"),
    ("NL", "Netherlands", "Нидерланды"), ("NO", "Norway", "Норвегия"), ("NZ", "New Zealand", "Новая Зеландия"), ("PH", "Philippines", "Филиппины"),
    ("PL", "Poland", "Польша"), ("PT", "Portugal", "Португалия"), ("RO", "Romania", "Румыния"), ("RS", "Serbia", "Сербия"),
    ("RU", "Russia", "Россия"), ("SA", "Saudi Arabia", "Саудовская Аравия"), ("SE", "Sweden", "Швеция"), ("SG", "Singapore", "Сингапур"),
    ("SI", "Slovenia", "Словения"), ("SK", "Slovakia", "Словакия"), ("TH", "Thailand", "Таиланд"), ("TJ", "Tajikistan", "Таджикистан"),
    ("TR", "Turkey", "Турция"), ("TW", "Taiwan", "Тайвань"), ("UA", "Ukraine", "Украина"), ("US", "United States", "США"),
    ("UZ", "Uzbekistan", "Узбекистан"), ("VN", "Vietnam", "Вьетнам"), ("ZA", "South Africa", "ЮАР"),
];

/// Two-letter code from a Twemoji flag id ("1f1f3-1f1f1" → "NL").
pub fn code_of_flag(id: &str) -> Option<String> {
    let mut out = String::new();
    for part in id.split('-') {
        let cp = u32::from_str_radix(part, 16).ok()?;
        if !(0x1F1E6..=0x1F1FF).contains(&cp) { return None; }
        out.push((b'A' + (cp - 0x1F1E6) as u8) as char);
    }
    (out.len() == 2).then_some(out)
}

pub fn name(code: &str) -> String {
    match NAMES.iter().find(|x| x.0 == code) {
        Some((_, en, ru)) => if crate::i18n::is_ru() { ru.to_string() } else { en.to_string() },
        None => code.to_string(),
    }
}
