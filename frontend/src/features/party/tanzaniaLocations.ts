/**
 * Tanzania's regions, councils and wards, for the address on a client's record.
 *
 * GENERATED -- do not edit by hand. Source: National Bureau of Statistics, 2022 Population and
 * Housing Census, "Administrative Units Population Distribution Report" (December 2022):
 *   Volume 1B, Tanzania Mainland -- 26 regions, 184 councils, 3956 wards
 *   Volume 1C, Tanzania Zanzibar -- 5 regions, 11 councils, 388 shehia
 *   https://www.nbs.go.tz/statistics/subtopic/administrative-reports
 *
 * The report lists wards under COUNCILS (Moshi District, Moshi Municipal), not under the older
 * districts, so the "District" box offers councils: that is the level a ward belongs to. Zanzibar
 * has no wards; its lowest unit, the shehia, fills the same box.
 *
 * A handful of the report's own slips are corrected in the generator (a heading spelt "DISTICT",
 * Kibiti's table titled "Mafia", "Buhingwe" for Buhigwe, quote marks around ward letters).
 *
 * The address is stored as plain text, so a record written before this list may hold a value that
 * is not on it. The form keeps such a value, marked "not on the list", rather than wiping it.
 */
export interface Council {
  name: string;
  wards: readonly string[];
}

export const TANZANIA_REGIONS: readonly { region: string; councils: readonly Council[] }[] = [
  {
    region: 'Arusha',
    councils: [
      { name: 'Arusha City', wards: ['Baraa', 'Daraja II', 'Elerai', 'Engutoto', 'Kaloleni', 'Kati', 'Kimandolu', 'Lemara', 'Levolosi', 'Moivaro', 'Moshono', 'Muriet', 'Ngarenaro', 'Olasiti', 'Olmoti', 'Oloirien', 'Osunyai Jr', 'Sakina', 'Sekei', 'Sinoni', 'Sokon I', 'Sombetini', 'Terrat', 'Themi', 'Unga Ltd'] },
      { name: 'Arusha District', wards: ['Bangata', 'Bwawani', 'Ilboru', 'Ilkiding\'a', 'Kimnyak', 'Kiranyi', 'Kisongo', 'Kiutu', 'Laroi', 'Lemanyata', 'Lengijave', 'Mateves', 'Mlangarini', 'Moivo', 'Musa', 'Mwandet', 'Nduruma', 'Oldonyosambu', 'Oldonyowass', 'Oljoro', 'Olmotonyi', 'Oloirien', 'Olturoto', 'Olturumet', 'Sambasha', 'Sokon II', 'Tarakwa'] },
      { name: 'Karatu District', wards: ['Baray', 'Buger', 'Daa', 'Endabash', 'Endamaghang', 'Endamarariek', 'Ganako', 'Kansay', 'Karatu', 'Mang\'ola', 'Mbulumbulu', 'Oldeani', 'Qurus', 'Rhotia'] },
      { name: 'Longido District', wards: ['Elang\'atadapash', 'Engarenaibor', 'Engikaret', 'Gelai Lumbwa', 'Gelai Meirugoi', 'Ilorienito', 'Kamwanga', 'Ketumbeine', 'Kimokouwa', 'Longido', 'Matale A', 'Mundarara', 'Namanga', 'Noondoto', 'Olmolog', 'Orbomba', 'Sinya', 'Tingatinga'] },
      { name: 'Meru District', wards: ['Akheri', 'Ambureni', 'Imbaseni', 'Kikatiti', 'Kikwe', 'King\'ori', 'Leguruki', 'Majengo', 'Maji ya Chai', 'Makiba', 'Malula', 'Maroroni', 'Maruvango', 'Mbuguni', 'Ngabobo', 'Ngarenanyuki', 'Nkoanekoli', 'Nkoanrua', 'Nkoaranga', 'Nkoarisambu', 'Poli', 'Seela Sing\'isi', 'Shambarai Burka', 'Songoro', 'Usariver', 'Uwiro'] },
      { name: 'Monduli District', wards: ['Engaruka', 'Engutoto', 'Esilalei', 'Lashaine', 'Lemooti', 'Lepurko', 'Lolkisale', 'Majengo', 'Makuyuni', 'Meserani', 'Mfereji', 'Migungani', 'Moita', 'Monduli Juu', 'Monduli Mjini', 'Mswakini', 'Mto wa Mbu', 'Naalarami', 'Selela', 'Sepeko'] },
      { name: 'Ngorongoro District', wards: ['Alailelai', 'Alaitolei', 'Arash', 'Digodigo', 'Enduleni', 'Engaresero', 'Enguserosambu', 'Eyasi', 'Kakesio', 'Kirangi', 'Maalon', 'Malambo', 'Misigiyo', 'Nainokanoka', 'Naiyobi', 'Ngoile', 'Ngorongoro', 'Olbalbal', 'Oldonyosambu', 'Oloipiri', 'Ololosokwan', 'Olorien/Magaiduru', 'Orgosorok', 'Pinyinyi', 'Piyaya', 'Sale', 'Samunge', 'Soitsambu'] },
    ],
  },
  {
    region: 'Dar es Salaam',
    councils: [
      { name: 'Dar es Salaam City', wards: ['Bonyokwa', 'Buguruni', 'Buyuni', 'Chanika', 'Gerezani', 'Gongolamboto', 'Ilala', 'Jangwani', 'Kariakoo', 'Kimanga', 'Kinyerezi', 'Kipawa', 'Kipunguni', 'Kisukuru', 'Kisutu', 'Kitunda', 'Kivukoni', 'Kivule', 'Kiwalani', 'Liwiti', 'Majohe', 'Mchafukoge', 'Mchikichini', 'Minazi Mirefu', 'Mnyamani', 'Msongola', 'Mzinga', 'Pugu', 'Pugu Station', 'Segerea', 'Tabata', 'Ukonga', 'Upanga Magharibi', 'Upanga Mashariki', 'Vingunguti', 'Zingiziwa'] },
      { name: 'Kigamboni Municipal', wards: ['Kibada', 'Kigamboni', 'Kimbiji', 'Kisarawe II', 'Mjimwema', 'Pembamnazi', 'Somangila', 'Tungi', 'Vijibweni'] },
      { name: 'Kinondoni Municipal', wards: ['Bunju', 'Hananasif', 'Kawe', 'Kigogo', 'Kijitonyama', 'Kinondoni', 'Kunduchi', 'Mabwepande', 'Magomeni', 'Makongo', 'Makumbusho', 'Mbezi Juu', 'Mbweni', 'Mikocheni', 'Msasani', 'Mwananyamala', 'Mzimuni', 'Ndugumbi', 'Tandale', 'Wazo'] },
      { name: 'Temeke Municipal', wards: ['Azimio', 'Buza', 'Chamazi', 'Chang\'ombe', 'Charambe', 'Keko', 'Kibondemaji', 'Kiburugwa', 'Kijichi', 'Kilakala', 'Kilungule', 'Kurasini', 'Makangarawe', 'Mbagala', 'Mbagala Kuu', 'Mianzini', 'Miburani', 'Mtoni', 'Sandali', 'Tandika', 'Temeke', 'Toangoma', 'Yombo Vituka'] },
      { name: 'Ubungo Municipal', wards: ['Goba', 'Kibamba', 'Kimara', 'Kwembe', 'Mabibo', 'Makuburi', 'Makurumla', 'Manzese', 'Mbezi', 'Mburahati', 'Msigani', 'Saranga', 'Sinza', 'Ubungo'] },
    ],
  },
  {
    region: 'Dodoma',
    councils: [
      { name: 'Bahi District', wards: ['Babayu', 'Bahi', 'Chali', 'Chibelela', 'Chifutuka', 'Chikola', 'Chipanga', 'Ibihwa', 'Ibugule', 'Ilindi', 'Kigwe', 'Lamaiti', 'Makanda', 'Mpalanga', 'Mpamantwa', 'Mpinga', 'Msisi', 'Mtitaa', 'Mundemu', 'Mwitikira', 'Nondwa', 'Zanka'] },
      { name: 'Chamwino District', wards: ['Buigiri', 'Chamwino', 'Chiboli', 'Chilonwa', 'Chinugulu', 'Dabalo', 'Fufu', 'Handali', 'Haneti', 'Huzi', 'Idifu', 'Igandu', 'Ikombolinga', 'Ikowa', 'Iringa Mvumi', 'Itiso', 'Loje', 'Majeleko', 'Makang\'wa', 'Manchali', 'Manda', 'Manzase', 'Membe', 'Mlowa Barabarani', 'Mlowa Bwawani', 'Mpwayungu', 'Msamalo', 'Msanga', 'Muungano', 'Mvumi Makulu', 'Mvumi Misheni', 'Nghahelezi', 'Nghambaku', 'Nhinhi', 'Segala', 'Zajilwa'] },
      { name: 'Chemba District', wards: ['Babayu', 'Chandama', 'Chemba', 'Churuku', 'Dalai', 'Farkwa', 'Goima', 'Gwandi', 'Jangalo', 'Kidoka', 'Kimaha', 'Kinyamsindo', 'Kwamtoro', 'Lahoda', 'Lalta', 'Makorongo', 'Mondo', 'Mpendo', 'Mrijo', 'Msaada', 'Ovada', 'Paranga', 'Sanzawa', 'Songolo', 'Soya', 'Tumbakose'] },
      { name: 'Dodoma City', wards: ['Chahwa', 'Chamwino', 'Chang\'ombe', 'Chigongwe', 'Chihanga', 'Dodoma Makulu', 'Hazina', 'Hombolo Bwawani', 'Hombolo Makulu', 'Ihumwa', 'Ipagala', 'Ipala', 'Iyumbu', 'Kikombo', 'Kikuyu Kaskazini', 'Kikuyu Kusini', 'Kilimani', 'Kiwanja cha Ndege', 'Kizota', 'Madukani', 'Majengo', 'Makole', 'Makutupora', 'Matumbulu', 'Mbabala', 'Mbalawala', 'Miyuji', 'Mkonze', 'Mnadani', 'Mpunguzi', 'Msalato', 'Mtumba', 'Nala', 'Nghong\'onha', 'Nkuhungu', 'Ntyuka', 'Nzuguni', 'Tambukareli', 'Uhuru', 'Viwandani', 'Zuzu'] },
      { name: 'Kondoa District', wards: ['Bereko', 'Bumbuta', 'Busi', 'Changaa', 'Haubi', 'Hondomairo', 'Itaswi', 'Itololo', 'Kalamba', 'Keikei', 'Kikilo', 'Kikore', 'Kinyasi', 'Kisese', 'Kwadelo', 'Masange', 'Mnenia', 'Pahi', 'Salanka', 'Soera', 'Thawi'] },
      { name: 'Kondoa Town', wards: ['Bolisa', 'Chemchem', 'Kilimani', 'Kingale', 'Kolo', 'Kondoa Mjini', 'Serya', 'Suruke'] },
      { name: 'Kongwa District', wards: ['Chamkoroma', 'Chitego', 'Chiwe', 'Hogoro', 'Iduo', 'Kibaigwa', 'Kongwa', 'Lenjulu', 'Makawa', 'Matongoro', 'Mkoka', 'Mlali', 'Mtanana', 'Ng\'humbi', 'Ngomai', 'Njoge', 'Pandambili', 'Sagara', 'Sejeli', 'Songambele', 'Ugogoni', 'Zoissa'] },
      { name: 'Mpwapwa District', wards: ['Berege', 'Chipogoro', 'Chitemo', 'Chunyu', 'Galigali', 'Godegode', 'Gulwe', 'Ipera', 'Iwondo', 'Kibakwe', 'Kimagai', 'Kingiti', 'Lufu', 'Luhundwa', 'Lumuma', 'Lupeta', 'Malolo', 'Mang\'aliza', 'Massa', 'Matomondo', 'Mazae', 'Mbuga', 'Mima', 'Mlembule', 'Mlunduzi', 'Mpwapwa Mjini', 'Mtera', 'Ng\'hambi', 'Pwaga', 'Rudi', 'Ving\'hawe', 'Wangi', 'Wotta'] },
    ],
  },
  {
    region: 'Geita',
    councils: [
      { name: 'Bukombe District', wards: ['Bugelenga', 'Bukombe', 'Bulangwa', 'Bulega', 'Busonzo', 'Butinzya', 'Igulwa', 'Iyogelo', 'Katente', 'Katome', 'Lyambamgongo', 'Namonge', 'Ng\'anzo', 'Runzewe Magharibi', 'Runzewe Mashariki', 'Ushirombo', 'Uyovu'] },
      { name: 'Chato District', wards: ['Bukome', 'Buseresere', 'Butengo rumasa', 'Buziku', 'Bwanga', 'Bwera', 'Bwina', 'Bwongera', 'Chato', 'Ichwankima', 'Ilemela', 'Ilyamchele', 'Iparamasa', 'Kachwamba', 'Kasenga', 'Katende', 'Kigongo', 'Makurugusi', 'Minkoto', 'Muganza', 'Muungano', 'Nyamirembe', 'Nyarutembo'] },
      { name: 'Geita District', wards: ['Bugalama', 'Bugulula', 'Bujula', 'Bukoli', 'Bukondo', 'Busanda', 'Butobela', 'Butundwe', 'Chigunga', 'Isulwabutundwe', 'Izumacheli', 'Kagu', 'Kakubilo', 'Kamena', 'Kamhanga', 'Kaseme', 'Katoma', 'Katoro', 'Lubanga', 'Ludete', 'Lwenzera', 'Magenge', 'Nkome', 'Nyachiluluma', 'Nyakagomba', 'Nyakamwaga', 'Nyalwanzaja', 'Nyamalimbe', 'Nyamboge', 'Nyamigota', 'Nyamwilolelwa', 'Nyarugusu', 'Nyaruyeye', 'Nyawilimilwa', 'Nzera', 'Rwamgasa', 'Senga'] },
      { name: 'Geita Town', wards: ['Bombambili', 'Buhalahala', 'Bulela', 'Bung\'wangoko', 'Ihanamilo', 'Kalangalala', 'Kanyala', 'Kasamwa', 'Mgusu', 'Mtakuja', 'Nyanguku', 'Nyankumbu', 'Shiloleli'] },
      { name: 'Mbogwe District', wards: ['Bukandwe', 'Bunigonzi', 'Ikobe', 'Ikunguigazi', 'Ilolangulu', 'Iponya', 'Isebya', 'Lugunga', 'Lulembela', 'Masumbwe', 'Mbogwe', 'Nanda', 'Ngemo', 'Nhomolwa', 'Nyakafulu', 'Nyasato', 'Ushirika'] },
      { name: 'Nyang\'hwale District', wards: ['Bukwimba', 'Busolwa', 'Izunya', 'Kaboha', 'Kafita', 'Kakora', 'Kharumwa', 'Mwingiro', 'Nundu', 'Nyabulanda', 'Nyamtukuza', 'Nyang\'hwale', 'Nyijundu', 'Nyugwa', 'Shabaka'] },
    ],
  },
  {
    region: 'Iringa',
    councils: [
      { name: 'Iringa District', wards: ['Idodi', 'Ifunda', 'Ilolompya', 'Itunundu', 'Izazi', 'Kalenga', 'Kihanga', 'Kihorogota', 'Kising\'a', 'Kiwere', 'Luhota', 'Lumuli', 'Lyamgungwe', 'Maboga', 'Magulilwa', 'Mahuninga', 'Malengamakali', 'Masaka', 'Mboliboli', 'Mgama', 'Migoli', 'Mlenge', 'Mlowa', 'Mseke', 'Nyang\'oro', 'Nzihi', 'Ulanda', 'Wasa'] },
      { name: 'Iringa Municipal', wards: ['Gangilonga', 'Igumbilo', 'Ilala', 'Isakalilo', 'Kihesa', 'Kitanzini', 'Kitwiru', 'Kwakilosa', 'Makorongoni', 'Mivinjeni', 'Mkimbizi', 'Mkwawa', 'Mlandege', 'Mshindo', 'Mtwivila', 'Mwangata', 'Nduli', 'Ruaha'] },
      { name: 'Kilolo District', wards: ['Boma la Ng\'ombe', 'Dabaga', 'Ibumu', 'Idete', 'Ihimbo', 'Ilula', 'Image', 'Irole', 'Kimala', 'Kising\'a', 'Lugalo', 'Mahenge', 'Masisiwe', 'Mlafu', 'Mtitu', 'Ng\'ang\'ange', 'Ng\'uruhe', 'Nyalumbu', 'Nyanzwa', 'Ruaha Mbuyuni', 'Udekwa', 'Uhambingeto', 'Ukumbi', 'Ukwega'] },
      { name: 'Mafinga Town', wards: ['Boma', 'Bumilayinga', 'Changarawe', 'Isalavanu', 'Kinyanambo', 'Rungemba', 'Sao Hill', 'Upendo', 'Wambi'] },
      { name: 'Mufindi District', wards: ['Idete', 'Idunda', 'Ifwagi', 'Igombavanu', 'Igowole', 'Ihalimba', 'Ihanu', 'Ihowanza', 'Ikongosi', 'Ikweha', 'Itandula', 'Kasanga', 'Kibengu', 'Kiyowela', 'Luhunga', 'Maduma', 'Makungu', 'Malangali', 'Mapanda', 'Mbalamaziwa', 'Mdabulo', 'Mninga', 'Mpanga Tazara', 'Mtambula', 'Mtwango', 'Nyololo', 'Sadani'] },
    ],
  },
  {
    region: 'Kagera',
    councils: [
      { name: 'Biharamulo District', wards: ['Biharamulo Mjini', 'Bisibo', 'Kabindi', 'Kalenge', 'Kaniha', 'Katahoka', 'Lusahunga', 'Nemba', 'Nyabusozi', 'Nyakahura', 'Nyamahanga', 'Nyamigogo', 'Nyantakara', 'Nyanza', 'Nyarubungo', 'Runazi', 'Ruziba'] },
      { name: 'Bukoba District', wards: ['Behendangabo', 'Bujugo', 'Butelankuzi', 'Ibwera', 'Izimbya', 'Kaagya', 'Kaibanja', 'Kanyangereko', 'Karabagaine', 'Kasharu', 'Katerero', 'Katoma', 'Katoro', 'Kemondo', 'Kibirizi', 'Kikomelo', 'Kishanje', 'Kishogo', 'Kyaitoke', 'Kyamulaile', 'Maruku', 'Mikoni', 'Mugajwale', 'Nyakato', 'Nyakibimbili', 'Rubafu', 'Rubale', 'Ruhunga', 'Rukoma'] },
      { name: 'Bukoba Municipal', wards: ['Bakoba', 'Bilele', 'Buhembe', 'Hamugembe', 'Ijuganyondo', 'Kagondo', 'Kahororo', 'Kashai', 'Kibeta', 'Kitendaguro', 'Miembeni', 'Nshambya', 'Nyanga', 'Rwamishenye'] },
      { name: 'Karagwe District', wards: ['Bugene', 'Bweranyange', 'Chanika', 'Chonyonyo', 'Igurwa', 'Ihanda', 'Ihembe', 'Kamagambo', 'Kanoni', 'Kayanga', 'Kibondo', 'Kihanga', 'Kiruruma', 'Kituntu', 'Ndama', 'Nyabiyonza', 'Nyaishozi', 'Nyakabanga', 'Nyakahanga', 'Nyakakika', 'Nyakasimbi', 'Rugera', 'Rugu'] },
      { name: 'Kyerwa District', wards: ['Bugara', 'Bugomora', 'Businde', 'Isingiro', 'Iteera', 'Kaisho', 'Kakanja', 'Kamuli', 'Kibare', 'Kibingo', 'Kikukuru', 'Kimuli', 'Kitwe', 'Kitwechenkura', 'Kyerwa', 'Mabira', 'Murongo', 'Nkwenda', 'Nyakatuntu', 'Nyaruzumbura', 'Rukuraijo', 'Rutunguru', 'Rwabwere', 'Songambele'] },
      { name: 'Missenyi District', wards: ['Bugandika', 'Bugorora', 'Buyango', 'Bwanjai', 'Gera', 'Ishozi', 'Ishunju', 'Kakunyu', 'Kanyigo', 'Kashenye', 'Kassambya', 'Kilimilile', 'Kitobo', 'Kyaka', 'Mabale', 'Minziro', 'Mushasha', 'Mutukula', 'Nsunga', 'Ruzinga'] },
      { name: 'Muleba District', wards: ['Biirabo', 'Bisheke', 'Buganguzi', 'Buhangaza', 'Bulyakashaju', 'Bumbire', 'Bureza', 'Burungura', 'Goziba', 'Gwanseli', 'Ibuga', 'Ijumbi', 'Ikondo', 'Ikuza', 'Izigo', 'Kabirizi', 'Kagoma', 'Kamachumu', 'Karambi', 'Kasharunga', 'Kashasha', 'Katoke', 'Kerebe', 'Kibanga', 'Kikuku', 'Kimwani', 'Kishanda', 'Kyebitembe', 'Mafumbo', 'Magata Karutanga', 'Mayondwe', 'Mazinga', 'Mubunda', 'Muhutwe', 'Muleba', 'Mushabago', 'Ngenge', 'Nshamba', 'Nyakabango', 'Nyakatanga', 'Ruhanga', 'Rulanda', 'Rutoro'] },
      { name: 'Ngara District', wards: ['Bugarama', 'Bukiriro', 'Kabanga', 'Kanazi', 'Kasulo', 'Keza', 'Kibimba', 'Kibogora', 'Kirushya', 'Mabawe', 'Mbuba', 'Muganza', 'Mugoma', 'Murukurazo', 'Murusagamba', 'Ngara Mjini', 'Ntobeye', 'Nyakisasa', 'Nyamagoma', 'Nyamiaga', 'Rulenge', 'Rusumo'] },
    ],
  },
  {
    region: 'Kaskazini Pemba',
    councils: [
      { name: 'Micheweni District', wards: ['Chamboni', 'Chimba', 'Kifundi', 'Kinowe', 'Kipange', 'Kiuyu Mbuyuni', 'Konde', 'Majenzi', 'Makangale', 'Maziwa Ng\'ombe', 'Micheweni', 'Mihogoni', 'Mjini Wingwi', 'Msuka Magharibi', 'Msuka Mashariki', 'Mtemani', 'Shanake', 'Shumba Mjini', 'Shumba Viamboni', 'Sizini', 'Tondooni', 'Tumbe Magharibi', 'Tumbe Mashariki', 'Wingwi Mapofu', 'Wingwi Njuguni'] },
      { name: 'Wete Town', wards: ['Bopwe', 'Chwale', 'Finya', 'Fundo', 'Gando', 'Jadida', 'Junguni', 'Kambini', 'Kangagani', 'Kinyasini', 'Kinyikani', 'Kipangani', 'Kisiwani', 'Kiungoni', 'Kiuyu Kigongoni', 'Kiuyu Minungwini', 'Kizimbani', 'Kojani', 'Limbani', 'Maziwani', 'Mchanga Mdogo', 'Mgogoni', 'Mjananza', 'Mlindo', 'Mpambani', 'Mtambwe Kaskazini', 'Mtambwe Kusini', 'Mtemani', 'Mzambarau Takao', 'Pandani', 'Pembeni', 'Piki', 'Selem', 'Shengejuu', 'Ukunjwi', 'Utaani'] },
    ],
  },
  {
    region: 'Kaskazini Unguja',
    councils: [
      { name: 'Kaskazini A District', wards: ['Banda Kuu', 'Bandamaji', 'Bwereu', 'Chaani Kubwa', 'Chaani Masingini', 'Chutama', 'Fukuchani', 'Gamba', 'Gomani', 'Jongowe', 'Juga Kuu', 'Kandwi', 'Kibeni', 'Kidombo', 'Kidoti', 'Kigomani', 'Kigongoni', 'Kigunda', 'Kijini Matemwe', 'Kikobweni', 'Kilimani Tazari', 'Kilindi', 'Kinyasini', 'Kipange', 'Kiungani', 'Kivunge', 'Matemwe Kaskazini', 'Matemwe Kusini', 'Mbuyutende', 'Mchangani', 'Mchenza Shauri', 'Mkokotoni', 'Mkwajuni', 'Moga', 'Mtakuja', 'Mto wa Pwani', 'Muwanda', 'Muwange', 'Pale', 'Pitanazako', 'Potoa', 'Pwani Mchangani', 'Tazari', 'Uvivini'] },
      { name: 'Kaskazini B Town', wards: ['Donge Karange', 'Donge Mbiji', 'Donge Mtambile', 'Donge Pwani', 'Donge Vijibweni', 'Fujoni', 'Kidanzini', 'Kilombero', 'Kinduni', 'Kiombamvua', 'Kiongwe Kidogo', 'Kisongoni', 'Kitope', 'Kiwengwa', 'Kwagube', 'Mafufuni', 'Mahonda', 'Majenzi', 'Makoba', 'Mangapwani', 'Matetema', 'Mbaleni', 'Mgambo', 'Misufini', 'Mkadini', 'Mkataleni', 'Mnyimbi', 'Njia ya Mtoni', 'Pangeni', 'Upenja', 'Zingwezingwe'] },
    ],
  },
  {
    region: 'Katavi',
    councils: [
      { name: 'Mlele District', wards: ['Ilela', 'Ilunde', 'Inyonga', 'Kamsisi', 'Nsenkwa', 'Utende'] },
      { name: 'Mpanda Municipal', wards: ['Ilembo', 'Kakese', 'Kashaulili', 'Kasokola', 'Kawajense', 'Kazima', 'Magamba', 'Majengo', 'Makanyagio', 'Misunkumilo', 'Mpanda Hotel', 'Mwamkulu', 'Nsemulwa', 'Shanwe', 'Uwanja wa Ndege'] },
      { name: 'Mpimbwe District', wards: ['Chamalendi', 'Ikuba', 'Kasansa', 'Kibaoni', 'Majimoto', 'Mamba', 'Mbede', 'Mwamapuli', 'Usevya'] },
      { name: 'Nsimbo District', wards: ['Ibindi', 'Itenka', 'Kanoge', 'Kapalala', 'Katumba', 'Litapunga', 'Machimboni', 'Mtapenda', 'Nsimbo', 'Sitalike', 'Ugalla', 'Uruwira (Urwira)'] },
      { name: 'Tanganyika District', wards: ['Bulamata', 'Ikola', 'Ilangu', 'Ipwaga', 'Isengule', 'Kabungu', 'Kapalamsenga', 'Karema', 'Kasekese', 'Katuma', 'Mishamo', 'Mnyagala', 'Mpandandogo', 'Mwese', 'Sibwesa', 'Tongwe'] },
    ],
  },
  {
    region: 'Kigoma',
    councils: [
      { name: 'Buhigwe District', wards: ['Biharu', 'Buhigwe', 'Bukuba', 'Janda', 'Kajana', 'Kibande', 'Kibwigwa', 'Kilelema', 'Kinazi', 'Mkatanga', 'Mubanga', 'Mugera', 'Muhinda', 'Munanila', 'Munyegera', 'Munzeze', 'Muyama', 'Mwayaya', 'Nyamugali', 'Rusaba'] },
      { name: 'Kakonko District', wards: ['Gwanumpu', 'Gwarama', 'Kakonko', 'Kanyonza', 'Kasanda', 'Kasuga', 'Katanga', 'Kiziguzigu', 'Mugunzu', 'Muhange', 'Nyabibuye', 'Nyamtukuza', 'Rugenge'] },
      { name: 'Kasulu District', wards: ['Asante Nyerere', 'Bugaga', 'Buhoro', 'Heru Ushingo', 'Kagera Nkanda', 'Kalela', 'Kigembe', 'Kitagata', 'Kitanga', 'Kurugongo', 'Kwaga', 'Makere', 'Muzye', 'Nyachenda', 'Nyakitonto', 'Nyamidaho', 'Nyamnyusi', 'Rungwe Mpya', 'Rusesa', 'Shunguliba', 'Titye'] },
      { name: 'Kasulu Town', wards: ['Heru Juu', 'Kigondo', 'Kimobwa', 'Kumnyika', 'Kumsenga', 'Msambara', 'Muganza', 'Muhunga', 'Murubona', 'Murufiti', 'Murusi', 'Mwilamvya', 'Nyansha', 'Nyumbigwa', 'Ruhita'] },
      { name: 'Kibondo District', wards: ['Bitare', 'Biturana', 'Bunyambo', 'Busagara', 'Busunzu', 'Itaba', 'Kagezi', 'Kibondo Mjini', 'Kitahana', 'Kizazi', 'Kumsenga', 'Kumwambu', 'Mabamba', 'Misezero', 'Mukabuye', 'Murungu', 'Nyaruyoba', 'Rugongwe', 'Rusohoko'] },
      { name: 'Kigoma District', wards: ['Bitale', 'Kagongo', 'Kagunga', 'Kalinzi', 'Kidahwe', 'Mahembe', 'Matendo', 'Mkigo', 'Mkongoro', 'Mungonya', 'Mwamgongo', 'Mwandiga', 'Nkungwe', 'Nyarubanda', 'Simbo', 'Ziwani'] },
      { name: 'Kigoma Municipal', wards: ['Bangwe', 'Buhanda', 'Businde', 'Buzebazeba', 'Gungu', 'Kagera', 'Kasimbu', 'Kasingirima', 'Katubuka', 'Kibirizi', 'Kigoma', 'Kipampa', 'Kitongoni', 'Machinjioni', 'Majengo', 'Mwanga Kaskazini', 'Mwanga Kusini', 'Rubuga', 'Rusimbi'] },
      { name: 'Uvinza District', wards: ['Basanza', 'Buhingu', 'Herembe', 'Igalula', 'Ilagala', 'Itebula', 'Kalya', 'Kandaga', 'Kazuramimba', 'Mganza', 'Mtegowanoti', 'Mwakizega', 'Nguruka', 'Sigunga', 'Sunuka', 'Uvinza'] },
    ],
  },
  {
    region: 'Kilimanjaro',
    councils: [
      { name: 'Hai District', wards: ['Bomang\'ombe', 'Bondeni', 'KIA', 'Machame Kaskazini', 'Machame Magharibi', 'Machame Mashariki', 'Machame Narumu', 'Machame Uroki', 'Masama Kati', 'Masama Kusini', 'Masama Magharibi', 'Masama Mashariki', 'Masama Rundugai', 'Mnadani', 'Muungano', 'Romu', 'Weruweru'] },
      { name: 'Moshi District', wards: ['Arusha Chini', 'Kahe Magharibi', 'Kahe Mashariki', 'Kibosho Kati', 'Kibosho Kirima', 'Kibosho Magharibi', 'Kibosho Mashariki', 'Kibosho Okaoni', 'Kilema Kaskazini', 'Kilema Kati', 'Kilema Kusini', 'Kimochi', 'Kindi', 'Kirua Vunjo Kusini', 'Kirua Vunjo Magharibi', 'Kirua Vunjo Mashariki', 'Mabogini', 'Makuyuni', 'Mamba Kaskazini', 'Mamba Kusini', 'Marangu Magharibi', 'Marangu Mashariki', 'Mbokomu', 'Mwika Kaskazini', 'Mwika Kusini', 'Njia Panda', 'Old Moshi Magharibi', 'Old Moshi Mashariki', 'Uru Kaskazini', 'Uru Kusini', 'Uru Mashariki', 'Uru Shimbwe'] },
      { name: 'Moshi Municipal', wards: ['Bomambuzi', 'Bondeni', 'Kaloleni', 'Karanga', 'Kiborloni', 'Kilimanjaro', 'Kiusa', 'Korongoni', 'Longuo B', 'Majengo', 'Mawenzi', 'Mfumuni', 'Miembeni', 'Mji Mpya', 'Msaranga', 'Ng\'ambo', 'Njoro', 'Pasua', 'Rau', 'Shirimatunda', 'Soweto'] },
      { name: 'Mwanga District', wards: ['Chomvu', 'Jipe', 'Kifula', 'Kighare', 'Kigonigoni', 'Kileo', 'Kilomeni', 'Kirongwe', 'Kirya', 'Kivisini', 'Kwakoa', 'Lang\'ata', 'Lembeni', 'Mgagao', 'Msangeni', 'Mwanga', 'Mwaniko', 'Ngujini', 'Shighatini', 'Toloha'] },
      { name: 'Rombo District', wards: ['Aleni', 'Chala', 'Holili', 'Katangara/Mrere', 'Kelamfua/Mokala', 'Kingachi', 'Kirongo Samanga', 'Kirwa Keni', 'Kisale Msaranga', 'Kitirima', 'Mahida', 'Makiidi', 'Mamsera', 'Manda', 'Marangu Kitowo', 'Mengeni', 'Mengwe', 'Motamburu Kitendeni', 'Mrao Keryo', 'Nanjara', 'Ngoyoni', 'Olele', 'Reha', 'Shimbi', 'Shimbi Kwandele', 'Tarakea Motamburu', 'Ubetu Kahe', 'Ushiri/Ikuini'] },
      { name: 'Same District', wards: ['Bangalala', 'Bendera', 'Bombo', 'Bwambo', 'Chome', 'Gavao-Saweni', 'Hedaru', 'Kalemawe', 'Kihurio', 'Kirangare', 'Kisima', 'Kisiwani', 'Lugulu', 'Mabilioni', 'Makanya', 'Maore', 'Mhezi', 'Mpinji', 'Mshewa', 'Msindo', 'Mtii', 'Mwembe', 'Myamba', 'Ndungu', 'Njoro', 'Ruvu', 'Same', 'Stesheni', 'Suji', 'Tae', 'Vudee', 'Vuje', 'Vumari', 'Vunta'] },
      { name: 'Siha District', wards: ['Biriri', 'Donyomurwak', 'Gararagua', 'Ivaeny', 'Karansi', 'Kashashi', 'Kirua', 'Livishi', 'Makiwaru', 'Miti Mirefu', 'Nasai', 'Ndumeti', 'Ngarenairobi', 'Olkolili', 'Ormelili', 'Sanya Juu', 'Songu'] },
    ],
  },
  {
    region: 'Kusini Pemba',
    councils: [
      { name: 'Chake Chake Town', wards: ['Chachani', 'Chanjaani', 'Chonga', 'Gombani', 'Kibokoni', 'Kichungwani', 'Kilindi', 'Kwale', 'Madungu', 'Matale', 'Mbuzini', 'Mchanga Mrima', 'Mfikiwa', 'Mgelema', 'Mgogoni', 'Michungwani', 'Mjini Ole', 'Mkoroshoni', 'Msingini', 'Mvumoni', 'Ndagoni', 'Ng\'ambwa', 'Ole', 'Pujini', 'Shungi', 'Tibirinzi', 'Uwandani', 'Vitongoji', 'Wara', 'Wawi', 'Wesha', 'Ziwani'] },
      { name: 'Mkoani Town', wards: ['Chambani', 'Changaweni', 'Chokocho', 'Chole', 'Chumbageni', 'Dodo', 'Jombwe', 'Kangani', 'Kendwa', 'Kengeja', 'Kisiwapanza', 'Kiwani', 'Kuukuu', 'Makombeni', 'Makoongwe', 'Mbuguani', 'Mbuyuni', 'Mchakwe', 'Mgagadu', 'Michenzani', 'Minazini', 'Mizingani', 'Mjimbini', 'Mkanyageni', 'Mkungu', 'Mtambile', 'Mtangani', 'Mwambe', 'Ng\'ombeni', 'Ngwachani', 'Shamiani', 'Shidi', 'Stahabu', 'Ukutini', 'Uweleni', 'Wambaa'] },
    ],
  },
  {
    region: 'Kusini Unguja',
    councils: [
      { name: 'Kati Town', wards: ['Bambi', 'Binguni', 'Bungi', 'Charawe', 'Cheju', 'Chwaka', 'Dunga Bweni', 'Dunga Kiembeni', 'Ghana', 'Jendele', 'Jumbi', 'Kiboje Mkwajuni', 'Kiboje Mwembeshauri', 'Kidimni', 'Kijibwemtu', 'Kikungwi', 'Koani', 'Machui', 'Marumbi', 'Mchangani Shamba', 'Mgeni Haji', 'Mitakawani', 'Miwani', 'Mpapa', 'Ndijani Mseweni', 'Ndijani Mwembepunda', 'Ng\'ambwa', 'Pagali', 'Pete', 'Pongwe', 'Tindini', 'Tunduni', 'Tunguu', 'Ubago', 'Ukongoroni', 'Umbuji', 'Unguja Ukuu Kaebona', 'Unguja Ukuu Kaepwani', 'Uroa', 'Uzi', 'Uzini', 'Zawiyani'] },
      { name: 'Kusini District', wards: ['Bwejuu', 'Dongwe', 'Jambiani Kibigija', 'Jambiani Kikadini', 'Kajengwa', 'Kibuteni', 'Kijini', 'Kiongoni', 'Kitogani', 'Kizimkazi Dimbani', 'Kizimkazi Mkunguni', 'Michamvi', 'Mtende', 'Muungoni', 'Muyuni A', 'Muyuni B', 'Muyuni C', 'Mzuri', 'Nganani', 'Paje', 'Tasani'] },
    ],
  },
  {
    region: 'Lindi',
    councils: [
      { name: 'Kilwa District', wards: ['Chumo', 'Kandawale', 'Kibata', 'Kikole', 'Kinjumbi', 'Kipatimu', 'Kiranjeranje', 'Kivinje', 'Lihimalyao', 'Likawage', 'Mandawa', 'Masoko', 'Miguruwe', 'Mingumbi', 'Miteja', 'Mitole', 'Namayuni', 'Nanjirinji', 'Njinjo', 'Pande', 'Somanga', 'Songosongo', 'Tingi'] },
      { name: 'Lindi Municipal', wards: ['Chikonji', 'Jamhuri', 'Kilangala', 'Kilolambwani', 'Kitomanga', 'Kitumbikwela', 'Kiwawa', 'Makonde', 'Matimba', 'Matopeni', 'Mbanja', 'Mchinga', 'Mikumbi', 'Milola', 'Mingoyo', 'Mipingo', 'Mitandi', 'Mnazimmoja', 'Msinjahili', 'Mtanda', 'Mvuleni', 'Mwenge', 'Nachingwea', 'Nangaru', 'Ndoro', 'Ng\'apa', 'Rahaleo', 'Rasbura', 'Rutamba', 'Tandangongoro', 'Wailes'] },
      { name: 'Liwale District', wards: ['Barikiwa', 'Kiangara', 'Kibutuka', 'Kichonda', 'Kimambi', 'Likongowele', 'Lilombe', 'Liwale \'B\'', 'Liwale Mjini', 'Makata', 'Mangirikiti', 'Mbaya', 'Mihumo', 'Mirui', 'Mkutano', 'Mlembwe', 'Mpigamiti', 'Nangando', 'Nangano', 'Ngongowele'] },
      { name: 'Mtama District', wards: ['Chiponda', 'Kiwalala', 'Longa', 'Majengo', 'Mandwanga', 'Mnara', 'Mnolela', 'Mtama', 'Mtua', 'Mtumbya', 'Nachunyu', 'Nahukahuka', 'Namangale', 'Namupa', 'Navanga', 'Nyangamara', 'Nyangao', 'Nyengedi', 'Pangatena', 'Sudi'] },
      { name: 'Nachingwea District', wards: ['Boma', 'Chiola', 'Chiumbati Shuleni', 'Kiegei', 'Kilimanihewa', 'Kilimarondo', 'Kipara Mnero', 'Kipara Mtua', 'Lionja', 'Marambo', 'Matekwe', 'Mbondo', 'Mchonda', 'Mitumbati', 'Mkoka', 'Mkotokuyana', 'Mnero Miembeni', 'Mnero Ngongo', 'Mpiruka', 'Mtua', 'Nachingwea Mjini', 'Naipanga', 'Naipingo', 'Namapwia', 'Namatula', 'Nambambo', 'Namikango', 'Nang\'ondo', 'Nangowe', 'Nditi', 'Ndomoni', 'Ngunichile', 'Raha leo', 'Ruponda', 'Stesheni', 'Ugawaji'] },
      { name: 'Ruangwa District', wards: ['Chibula', 'Chienjele', 'Chinongwe', 'Chunyu', 'Likunja', 'Luchelegwa', 'Makanjiro', 'Malolo', 'Mandarawe', 'Mandawa', 'Matambarale', 'Mbekenyera', 'Mbwemkuru (Machang\'anja)', 'Mnacho', 'Nachingwea', 'Nambilanje', 'Namichiga', 'Nandagala', 'Nanganga', 'Narungombe', 'Nkowe', 'Ruangwa'] },
    ],
  },
  {
    region: 'Manyara',
    councils: [
      { name: 'Babati District', wards: ['Arri', 'Ayalagaya', 'Ayasanda', 'Bashnet', 'Boay', 'Dabil', 'Dareda', 'Duru', 'Endakiso', 'Gallapo', 'Gidas', 'Kiru', 'Kisangaji', 'Madunga', 'Magara', 'Magugu', 'Mamire', 'Mwada', 'Nar', 'Nkaiti', 'Qameyu', 'Qash', 'Riroda', 'Secheda', 'Ufana'] },
      { name: 'Babati Town', wards: ['Babati', 'Bagara', 'Bonga', 'Maisaka', 'Mutuka', 'Nangara', 'Sigino', 'Singe'] },
      { name: 'Hanang District', wards: ['Balang\'dalalu', 'Bassodesh', 'Bassotu', 'Dawar', 'Dirma', 'Dumbeta', 'Endagaw', 'Endasak', 'Endasiwold', 'Ganana', 'Garawja', 'Gehandu', 'Gendabi', 'Getanuwas', 'Gidahababieg', 'Gisambalang', 'Gitting', 'Hidet', 'Hirbadaw', 'Ishponga', 'Jorodom', 'Katesh', 'Laghanga', 'Lalaji', 'Masakta', 'Masqaroda', 'Measkron', 'Mogitu', 'Mulbadaw', 'Nangwa', 'Simbay', 'Sirop', 'Wareta'] },
      { name: 'Kiteto District', wards: ['Bwagamoyo', 'Bwawani', 'Chapakazi', 'Dongo', 'Dosidosi', 'Engusero', 'Kaloleni', 'Kibaya', 'Kijungu', 'Laiseri', 'Lengatei', 'Loolera', 'Magungu', 'Makame', 'Matui', 'Namelock', 'Ndedo', 'Ndirgishi', 'Njoro', 'Olboloti', 'Partimbo', 'Songambele', 'Sunya'] },
      { name: 'Mbulu District', wards: ['Bashay', 'Dinamu', 'Dongobesh', 'Endahagichan', 'Endamilay', 'Eshkesh', 'Geterer', 'Gidhim', 'Haydarer', 'Haydom', 'Labay', 'Maghang', 'Maretadu', 'Masieda', 'Masqaroda', 'Tumati', 'Yaeda Ampa', 'Yaeda Chini'] },
      { name: 'Mbulu Town', wards: ['Ayamaami', 'Ayamohe', 'Bargish', 'Daudi', 'Endagikot', 'Gehandu', 'Gunyoda', 'Imboru', 'Kainam', 'Marang', 'Murray', 'Nahasey', 'Nambis', 'Sanu Baray', 'Silaloda', 'Tlawi', 'Uhuru'] },
      { name: 'Simanjiro District', wards: ['Emboreet', 'Endiamutu', 'Endonyongijape', 'Kitwai', 'Komolo', 'Langai', 'Loiborsiret', 'Loiborsoit', 'Mirerani', 'Msitu wa Tembo', 'Naberera', 'Naisinyai', 'Ngorika', 'Oljoro No.5', 'Orkesumet', 'Ruvu Remit', 'Shambarai', 'Terrat'] },
    ],
  },
  {
    region: 'Mara',
    councils: [
      { name: 'Bunda District', wards: ['Butimba', 'Chitengule', 'Hunyari', 'Igundu', 'Iramba', 'Kasuguti', 'Ketare', 'Kibara', 'Kisorya', 'Mihingo', 'Mugeta', 'Namhula', 'Nampindi', 'Nansimo', 'Neruma', 'Nyamang\'uta', 'Nyamihyoro', 'Nyamuswa', 'Salama'] },
      { name: 'Bunda Town', wards: ['Balili', 'Bunda Mjini', 'Bunda Stoo', 'Guta', 'Kabarimu', 'Kabasa', 'Kunzugu', 'Manyamanyama', 'Mcharo', 'Nyamakokoto', 'Nyasura', 'Nyatwali', 'Sazira', 'Wariku'] },
      { name: 'Butiama District', wards: ['Bisumwa', 'Buhemba', 'Bukabwa', 'Buruma', 'Busegwe', 'Buswahili', 'Butiama', 'Butuguri', 'Bwiregi', 'Kamugegi', 'Kukirango', 'Kyanyari', 'Masaba', 'Mirwa', 'Muriaza', 'Nyamimange', 'Nyankanga', 'Sirorisimba'] },
      { name: 'Musoma District', wards: ['Bugoji', 'Bugwema', 'Bukima', 'Bukumi', 'Bulinga', 'Busambara', 'Bwasi', 'Etaro', 'Ifulifu', 'Kiriba', 'Makojo', 'Mugango', 'Murangi', 'Musanja', 'Nyakatende', 'Nyambono', 'Nyamrandirira', 'Nyegina', 'Rusoli', 'Suguti', 'Tegeruka'] },
      { name: 'Musoma Municipal', wards: ['Buhare', 'Bweri', 'Iringo', 'Kamunyonge', 'Kigera', 'Kitaji', 'Kwangwa', 'Makoko', 'Mshikamano', 'Mukendo', 'Mwigobero', 'Mwisenge', 'Nyakato', 'Nyamatare', 'Nyasho', 'Rwamlimi'] },
      { name: 'Rorya District', wards: ['Baraki', 'Bukura', 'Bukwe', 'Goribe', 'Ikoma', 'Kigunga', 'Kinyenche', 'Kirogo', 'Kisumwa', 'Kitembe', 'Komuge', 'Koryo', 'Kyang\'ombe', 'Kyangasaga', 'Mirare', 'Mkoma', 'Nyaburongo', 'Nyahongo', 'Nyamagaro', 'Nyamtinga', 'Nyamunga', 'Nyathorongo', 'Rabour', 'Raranya', 'Roche', 'Tai'] },
      { name: 'Serengeti District', wards: ['Busawe', 'Geitasamo', 'Ikoma', 'Issenye', 'Kebanchabancha', 'Kenyamonta', 'Kisaka', 'Kisangura', 'Kyambahi', 'Machochwe', 'Magange', 'Majimoto', 'Manchira', 'Matare', 'Mbalibali', 'Morotonga', 'Mosongo', 'Mugumu', 'Nagusi', 'Natta', 'Nyamatare', 'Nyambureti', 'Nyamoko', 'Nyansurura', 'Rigicha', 'Ring\'wani', 'Rung\'abure', 'Sedeco', 'Stendi Kuu', 'Uwanja wa Ndege'] },
      { name: 'Tarime District', wards: ['Binagi', 'Bumera', 'Ganyange', 'Gorong\'a', 'Gwitiryo', 'Itiryo', 'Kemambo', 'Kibasuka', 'Kiore', 'Komaswa', 'Kwihancha', 'Manga', 'Matongo', 'Mbogi', 'Muriba', 'Mwema', 'Nyakonga', 'Nyamwaga', 'Nyansincha', 'Nyanungu', 'Nyarero', 'Nyarokoba', 'Pemba', 'Regicheri', 'Sirari', 'Susuni'] },
      { name: 'Tarime Town', wards: ['Bomani', 'Kenyamanyori', 'Ketare', 'Nkende', 'Nyamisangura', 'Nyandoto', 'Sabasaba', 'Turwa'] },
    ],
  },
  {
    region: 'Mbeya',
    councils: [
      { name: 'Busokelo District', wards: ['Isange', 'Itete', 'Kabula', 'Kambasegela', 'Kandete', 'Kisegese', 'Lufilyo', 'Lupata', 'Luteba', 'Lwangwa', 'Mpata', 'Mpombo', 'Ntaba'] },
      { name: 'Chunya District', wards: ['Bwawani', 'Chalangwa', 'Chokaa', 'Ifumbo', 'Itewe', 'Kambikatoto', 'Kasanga', 'Lualaje', 'Lupa', 'Mafyeko', 'Makongolosi', 'Mamba', 'Matundasi', 'Matwiga', 'Mbugani', 'Mkola', 'Mtanila', 'Nkung\'ungu', 'Sangambi', 'Upendo'] },
      { name: 'Kyela District', wards: ['Bondeni', 'Bujonde', 'Busale', 'Ibanda', 'Ikama', 'Ikimba', 'Ikolo', 'Ipande', 'Ipinda', 'Ipyana', 'Itope', 'Itunge', 'Kajunjumele', 'Katumbasongwe', 'Kyela', 'Lusungo', 'Mababu', 'Makwale', 'Matema', 'Mbugani', 'Mikoroshoni', 'Muungano', 'Mwanganyanga', 'Mwaya', 'Ndandalo', 'Ndobo', 'Ngana', 'Ngonga', 'Njisi', 'Nkokwa', 'Nkuyu', 'Serengeti', 'Talatala'] },
      { name: 'Mbarali District', wards: ['Chimala', 'Igava', 'Igurusi', 'Ihahi', 'Imalilo Songwe', 'Ipwani', 'Itamboleo', 'Kongolo', 'Lugelele', 'Luhanga', 'Madibira', 'Mahongole', 'Mapogoro', 'Mawindi', 'Miyombweni', 'Mwatenga', 'Ruiwa', 'Rujewa', 'Ubaruku', 'Utengule Usangu'] },
      { name: 'Mbeya City', wards: ['Forest', 'Ghana', 'Iduda', 'Iganjo', 'Iganzo', 'Igawilo', 'Ilemi', 'Ilomba', 'Isanga', 'Isyesye', 'Itagano', 'Itende', 'Itezi', 'Itiji', 'Iwambi', 'Iyela', 'Iyunga', 'Iziwa', 'Kalobe', 'Maanga', 'Mabatini', 'Maendeleo', 'Majengo', 'Mbalizi Road', 'Mwakibete', 'Mwasanga', 'Mwasenkwa', 'Nonde', 'Nsalaga', 'Nsoho', 'Nzovwe', 'Ruanda', 'Sinde', 'Sisimba', 'Tembela', 'Uyole'] },
      { name: 'Mbeya District', wards: ['Bonde la Songwe', 'Igale', 'Igoma', 'Ihango', 'Ijombe', 'Ikukwa', 'Ilembo', 'Ilungu', 'Inyala', 'Isuto', 'Itawa', 'Itewe', 'Iwiji', 'Iwindi', 'Iyunga Mapinduzi', 'Izyra', 'Lwanjilo', 'Maendeleo', 'Masoko', 'Mjele', 'Mshewe', 'Nsalala', 'Santilya', 'Shizuvi', 'Swaya', 'Tembela', 'Ulenje', 'Utengule/Usongwe'] },
      { name: 'Rungwe District', wards: ['Bagamoyo', 'Bujela', 'Bulyaga', 'Ibighi', 'Ikuti', 'Ilima', 'Iponjola', 'Isongole', 'Itagata', 'Kawetele', 'Kinyala', 'Kisiba', 'Kisondela', 'Kiwira', 'Kyimo', 'Lufingo', 'Lupepo', 'Makandana', 'Malindo', 'Masebe', 'Masoko', 'Masukulu', 'Matwebe', 'Mpuguso', 'Msasani', 'Ndanto', 'Nkunga', 'Suma', 'Swaya'] },
    ],
  },
  {
    region: 'Mjini Magharibi',
    councils: [
      { name: 'Magharibi A Municipal', wards: ['Bububu', 'Bumbwisudi', 'Chemchem', 'Chuini', 'Dole', 'Hawaii', 'Kama', 'Kianga', 'Kibweni', 'Kihinani', 'Kijichi', 'Kikaangoni', 'Kizimbani', 'Kwa Goa', 'Masingini', 'Mbuzini', 'Mfenesini', 'Michikichini', 'Mto pepo', 'Mtofaani', 'Mtoni', 'Mtoni ChemChem', 'Mtoni Kidatu', 'Muembe Mchomeke', 'Munduli', 'Mwakaje', 'Mwanyanya', 'Mwera', 'Sharifu Msa', 'Uholanzi', 'Welezo'] },
      { name: 'Magharibi B Municipal', wards: ['Bweleo', 'Chukwani', 'Chunga', 'Dimani', 'Fumba', 'Fuoni Kipungani', 'Fuoni Migombani', 'Jitimai', 'Kibondeni', 'Kiembesamaki', 'Kijitoupele', 'Kinuni', 'Kisauni', 'Kombeni', 'Kwa Mchina', 'Magogoni', 'Mambosasa', 'Maungani', 'Mbweni', 'Melinne', 'Michungwani', 'Mikarafuuni', 'Mnarani', 'Mombasa', 'Muembe Majogoo', 'Mwanakwerekwe', 'Nyamanzi', 'Pangawe', 'Shakani', 'Sokoni', 'Taveta', 'Tomondo', 'Uwandani', 'Uzi'] },
      { name: 'Mjini Municipal', wards: ['Amani', 'Banko', 'Chumbuni', 'Gulioni', 'Jang\'ombe', 'Karakana', 'Kidongo Chekundu', 'Kikwajuni Bondeni', 'Kikwajuni Juu', 'Kilimahewa Bondeni', 'Kilimahewa Juu', 'Kilimani', 'Kiponda', 'Kisimamajongoo', 'Kisiwandui', 'Kwa Wazee', 'Kwaalimsha', 'Kwaalinatu', 'Kwabintiamrani', 'Kwahani', 'Kwamtipura', 'Kwamtumwajeni', 'Magomeni', 'Makadara', 'Malindi', 'Mapinduzi', 'Maruhubi', 'Masumbani', 'Matarumbeta', 'Mboriborini', 'Mchangani', 'Meya', 'Miembeni', 'Migombani', 'Mikunguni', 'Mitiulaya', 'Mkele', 'Mkunazini', 'Mlandege', 'Mnazimmoja', 'Mpendae', 'Muembeshauri', 'Muembetanga', 'Muungano', 'Mwembeladu', 'MwembeMadema', 'Mwembemakumbi', 'Nyerere', 'Rahaleo', 'Saateni', 'Sebleni', 'Shangani', 'Shaurimoyo', 'Sogea', 'Urusi', 'Vikokotoni'] },
    ],
  },
  {
    region: 'Morogoro',
    councils: [
      { name: 'Gairo District', wards: ['Chagongwe', 'Chakwale', 'Chanjale', 'Chigela', 'Gairo', 'Idibo', 'Italagwe', 'Iyogwe', 'Kibedya', 'Leshata', 'Madege', 'Magoweko', 'Mandege', 'Mkalama', 'Msingisi', 'Nongwe', 'Rubeho', 'Ukwamani'] },
      { name: 'Ifakara Town', wards: ['Ifakara', 'Katindiuka', 'Kibaoni', 'Kiberege', 'Kidatu', 'Kisawasawa', 'Lipangalala', 'Lumemo', 'Mang\'ula', 'Mang\'ula \'B\'', 'Mbasa', 'Michenga', 'Mkula', 'Mlabani', 'Msolwa Station', 'Mwaya', 'Sanje', 'Signal', 'Viwanjasitini'] },
      { name: 'Kilosa District', wards: ['Berega', 'Chanzuru', 'Dumila', 'Kasiki', 'Kidete', 'Kidodi', 'Kilangali', 'Kimamba A', 'Kimamba B', 'Kisanga', 'Kitete', 'Lumbiji', 'Lumuma', 'Mabula', 'Mabwerebwere', 'Madoto', 'Magole', 'Magomeni', 'Magubike', 'Maguha', 'Malolo', 'Mamboya', 'Masanze', 'Mbigiri', 'Mbumi', 'Mhenda', 'Mikumi', 'Mkwatani', 'Msowero', 'Mtumbatu', 'Mvumi', 'Parakuyo', 'Ruaha', 'Rudewa', 'Ruhembe', 'Tindiga', 'Ulaya', 'Uleling\'ombe', 'Vidunda', 'Zombo'] },
      { name: 'Malinyi District', wards: ['Biro', 'Igawa', 'Itete', 'Kilosa Mpepo', 'Malinyi', 'Mtimbira', 'Ngoheranga', 'Njiwa', 'Sofi', 'Usangule'] },
      { name: 'Mlimba District', wards: ['Ching\'anda', 'Chisano', 'Chita', 'Idete', 'Igima', 'Kalengakelu', 'Kamwene', 'Masagati', 'Mbingu', 'Mchombe', 'Mlimba', 'Mngeta', 'Mofu', 'Namwawala', 'Uchindile', 'Utengule'] },
      { name: 'Morogoro District', wards: ['Bungu', 'Bwakira Chini', 'Bwakira Juu', 'Gwata', 'Kasanga', 'Kibogwa', 'Kibuko', 'Kibungo', 'Kidugalo', 'Kinole', 'Kiroka', 'Kisaki', 'Kisemu', 'Kolero', 'Konde', 'Lundi', 'Matuli', 'Mikese', 'Mkambalani', 'Mkulazi', 'Mkuyuni', 'Mngazi', 'Mtombozi', 'Mvuha', 'Ngerengere', 'Selembala', 'Singisa', 'Tawa', 'Tegetero', 'Tomondo', 'Tununguo'] },
      { name: 'Morogoro Municipal', wards: ['Bigwa', 'Boma', 'Chamwino', 'Kauzeni', 'Kichangani', 'Kihonda', 'Kihonda Maghorofani', 'Kilakala', 'Kingo', 'Kingolwira', 'Kiwanja cha Ndege', 'Luhungo', 'Lukobe', 'Mafiga', 'Mafisa', 'Magadu', 'Mazimbu', 'Mbuyuni', 'Mindu', 'Mji Mkuu', 'Mji Mpya', 'Mkundi', 'Mlimani', 'Mwembesongo', 'Mzinga', 'Sabasaba', 'Sultan Area', 'Tungi', 'Uwanja wa Taifa'] },
      { name: 'Mvomero District', wards: ['Bunduki', 'Dakawa', 'Diongoya', 'Doma', 'Hembeti', 'Homboza', 'Kanga', 'Kibati', 'Kikeo', 'Kinda', 'Kweuma', 'Langali', 'Luale', 'Lubungo', 'Mangae', 'Maskati', 'Melela', 'Mgeta', 'Mhonda', 'Mkindo', 'Mlali', 'Msongozi', 'Mtibwa', 'Mvomero', 'Mziha', 'Mzumbe', 'Nyandira', 'Pemba', 'Sungaji', 'Tchenzema'] },
      { name: 'Ulanga District', wards: ['Chilombola', 'Euga', 'Ilonga', 'Iragua', 'Isongo', 'Ketaketa', 'Kichangani', 'Lukande', 'Lupiro', 'Mahenge Mjini', 'Mawasiliano', 'Mbuga', 'Milola', 'Minepa', 'Msogezi', 'Mwaya', 'Nawenge', 'Ruaha', 'Sali', 'Uponera', 'Vigoi'] },
    ],
  },
  {
    region: 'Mtwara',
    councils: [
      { name: 'Masasi District', wards: ['Chigugu', 'Chikoropola', 'Chikukwe', 'Chikundi', 'Chikunja', 'Chiungutwa', 'Chiwale', 'Chiwata', 'Lipumburu', 'Lukuledi', 'Lulindi', 'Lupaso', 'Makong\'onda', 'Mbuyuni', 'Mchauru', 'Mijelejele', 'Mitesa', 'Mkululu', 'Mlingula', 'Mnavira', 'Mpanyani', 'Mpeta', 'Mpindimbi', 'Msikisi', 'Mwena', 'Namajani', 'Namalenga', 'Namatutwe', 'Namwanga', 'Nanganga', 'Nangoo', 'Nanjota', 'Ndanda', 'Sindano'] },
      { name: 'Masasi Town', wards: ['Chanikanguo', 'Jida', 'Marika', 'Matawale', 'Migongo', 'Mkomaindo', 'Mkuti', 'Mtandi', 'Mumbaka', 'Mwenge Mtapika', 'Napupa', 'Nyasa', 'Sululu', 'Temeke'] },
      { name: 'Mtwara District', wards: ['Dihimba', 'Kitere', 'Libobe', 'Lipwidi', 'Madimba', 'Mahurunga', 'Mangopachanne', 'Mayanga', 'Mbawala', 'Mkunwa', 'Moma', 'Mpapura', 'Msanga Mkuu', 'Msimbati', 'Muungano', 'Nalingu', 'Nanguruwe', 'Naumbu', 'Ndumbwe', 'Tangazo', 'Ziwani'] },
      { name: 'Mtwara Municipal', wards: ['Chikongola', 'Chuno', 'Jangwani', 'Kisungule', 'Likombe', 'Magengeni', 'Magomeni', 'Majengo', 'Mitengo', 'Mtawanya', 'Mtonya', 'Naliendele', 'Rahaleo', 'Reli', 'Shangani', 'Tandika', 'Ufukoni', 'Vigaeni'] },
      { name: 'Nanyamba Town', wards: ['Chawi', 'Dinyecha', 'Hinju', 'Kiromba', 'Kitaya', 'Kiyanga', 'Mbembaleo', 'Milangominne', 'Mnima', 'Mnongodi', 'Mtimbwilimbwi', 'Mtiniko', 'Namtumbuka', 'Nanyamba', 'Nitekela', 'Njengwa', 'Nyundo'] },
      { name: 'Nanyumbu District', wards: ['Chipuputa', 'Kamundi', 'Kilimanihewa', 'Likokona', 'Lumesule', 'Mangaka', 'Maratani', 'Masuguru', 'Michiga', 'Mikangaula', 'Mkonona', 'Mnanje', 'Nandete', 'Nangomba', 'Nanyumbu', 'Napacho', 'Sengenya'] },
      { name: 'Newala District', wards: ['Chihangu', 'Chilangala', 'Chitekete', 'Chiwonga', 'Kitangari', 'Makukwe', 'Malatu', 'Maputi', 'Mchemo', 'Mdimba Mpelepele', 'Mikumbi', 'Mkoma II', 'Mkwedu', 'Mnyambe', 'Mnyeu', 'Mpwapwa', 'Mtopwa', 'Mtunguru', 'Muungano', 'Nakahako', 'Nambali', 'Nandwahi'] },
      { name: 'Newala Town', wards: ['Julia', 'Luchingu', 'Mahumbika', 'Makonga', 'Makote', 'Mcholi I', 'Mcholi II', 'Mkulung\'ulu', 'Mkunya', 'Mnekachi', 'Mtonya', 'Mtumachi', 'Namiyonga', 'Nanguruwe', 'Nangwala', 'Tulindane'] },
      { name: 'Tandahimba District', wards: ['Chaume', 'Chikongola', 'Chingungwe', 'Dinduma', 'Kitama', 'Kwanyama', 'Litehu', 'Luagala', 'Lukokoda', 'Lyenje', 'Mahuta', 'Malopokelo', 'Maundo', 'Mchichira', 'Mdimba Mnyoma', 'Michenjele', 'Mihambwe', 'Milongodi', 'Miuta', 'Mkonjowano', 'Mkoreha', 'Mkundi', 'Mkwedu', 'Mkwiti', 'Mndumbwe', 'Mnyawa', 'Nambahu', 'Namikupa', 'Nanhyanga', 'Naputa', 'Ngunja', 'Tandahimba'] },
    ],
  },
  {
    region: 'Mwanza',
    councils: [
      { name: 'Buchosa District', wards: ['Bangwe', 'Bugoro', 'Buhama', 'Bukokwa', 'Bulyaheke', 'Bupandwa', 'Iligamba', 'Irenza', 'Kafunzo', 'Kalebezo', 'Kasisa', 'Katwe', 'Kazunzu', 'Lugata', 'Luharanyonga', 'Maisome', 'Nyakaliro', 'Nyakasasa', 'Nyakasungwa', 'Nyanzenda', 'Nyehunge'] },
      { name: 'Ilemela Municipal', wards: ['Bugogwa', 'Buswelu', 'Buzuruga', 'Ibungilo', 'Ilemela', 'Kahama', 'Kawekamo', 'Kayenze', 'Kirumba', 'Kiseke', 'Kitangiri', 'Mecco', 'Nyakato', 'Nyamanoro', 'Nyamhongolo', 'Nyasaka', 'Pasiansi', 'Sangabuye', 'Shibula'] },
      { name: 'Kwimba District', wards: ['Bugando', 'Bungulwa', 'Bupamwa', 'Fukalo', 'Hungumalwa', 'Igongwa', 'Ilula', 'Iseni', 'Kikubiji', 'Lyoma', 'Maligisu', 'Malya', 'Mantare', 'Mhande', 'Mwabomba', 'Mwagi', 'Mwakilyambiti', 'Mwamala', 'Mwandu', 'Mwang\'halanga', 'Mwankulwe', 'Ng\'hundi', 'Ngudu', 'Ngulla', 'Nkalalo', 'Nyambiti', 'Nyamilama', 'Shilembo', 'Sumve', 'Walla'] },
      { name: 'Magu District', wards: ['Buhumbi', 'Bujashi', 'Bujora', 'Bukandwe', 'Chabula', 'Isandula', 'Itumbili', 'Jinjimili', 'Kabila', 'Kahangara', 'Kandawe', 'Kisesa', 'Kitongo Sima', 'Kongolo', 'Lubugu', 'Lutale', 'Magu Mjini', 'Mwamabanza', 'Mwamanga', 'Ng\'haya', 'Nkungulu', 'Nyanguge', 'Nyigogo', 'Shishani', 'Sukuma'] },
      { name: 'Misungwi District', wards: ['Buhingo', 'Buhunda', 'Bulemeji', 'Busongo', 'Fella', 'Gulumungu', 'Idetemya', 'Igokelo', 'Ilujamate', 'Isenengeja', 'Kanyelele', 'Kasololo', 'Kijima', 'Koromije', 'Lubili', 'Mabuki', 'Mamaye', 'Mbarika', 'Misasi', 'Misungwi', 'Mondo', 'Mwaniko', 'Nhundulu', 'Shilalo', 'Sumbugu', 'Ukiriguru', 'Usagara'] },
      { name: 'Mwanza City', wards: ['Buhongwa', 'Butimba', 'Igogo', 'Igoma', 'Isamilo', 'Kishili', 'Luchelele', 'Lwanhima', 'Mabatini', 'Mahina', 'Mbugani', 'Mhandu', 'Mirongo', 'Mkolani', 'Mkuyuni', 'Nyamagana', 'Nyegezi', 'Pamba'] },
      { name: 'Sengerema District', wards: ['Bitoto', 'Busisi', 'Buyagu', 'Buzilasoga', 'Chifunfu', 'Ibisabageni', 'Ibondo', 'Igalula', 'Igulumuki', 'Kagunga', 'Kahumulo', 'Kasenyi', 'Kasungamile', 'Katunguru', 'Kishinda', 'Mission', 'Mwabaluhi', 'Ngoma', 'Nyamatongo', 'Nyamazugo', 'Nyamizeze', 'Nyampande', 'Nyampulukano', 'Nyatukara', 'Sima', 'Tabaruka'] },
      { name: 'Ukerewe District', wards: ['Bukanda', 'Bukiko', 'Bukindo', 'Bukongo', 'Bukungu', 'Bwiro', 'Bwisya', 'Igalla', 'Ilangala', 'Irugwa', 'Kagera', 'Kagunguli', 'Kakerege', 'Kakukuru', 'Mukituntu', 'Muriti', 'Murutunguru', 'Nakatunguru', 'Namagondo', 'Namilembe', 'Nansio', 'Nduruma', 'Ngoma', 'Nkilizya', 'Nyamanga'] },
    ],
  },
  {
    region: 'Njombe',
    councils: [
      { name: 'Ludewa District', wards: ['Ibumi', 'Iwela', 'Kilondo', 'Lifuma', 'Luana', 'Lubonde', 'Ludende', 'Ludewa', 'Lugarawa', 'Luilo', 'Lumbila', 'Lupanga', 'Lupingu', 'Madilu', 'Madope', 'Makonde', 'Manda', 'Masasi', 'Mavanga', 'Mawengi', 'Milo', 'Mkongobaki', 'Mlangali', 'Mundindi', 'Nkomang\'ombe', 'Ruhuhu'] },
      { name: 'Makambako Town', wards: ['Kitandililo', 'Kitisi', 'Kivavi', 'Lyamkena', 'Maguvani', 'Mahongole', 'Majengo', 'Makambako', 'Mjimwema', 'Mlowa', 'Mwembetogwa', 'Utengule'] },
      { name: 'Makete District', wards: ['Bulongwa', 'Ikuwo', 'Iniho', 'Ipelele', 'Ipepo', 'Isapulano', 'Itundu', 'Iwawa', 'Kigala', 'Kigulu', 'Kinyika', 'Kipagalo', 'Kitulo', 'Lupalilo', 'Lupila', 'Luwumbu', 'Mang\'oto', 'Matamba', 'Mbalatse', 'Mfumbi', 'Mlondwe', 'Tandala', 'Ukwama'] },
      { name: 'Njombe District', wards: ['Idamba', 'Igongolo', 'Ikondo', 'Ikuna', 'Kichiwa', 'Kidegembye', 'Lupembe', 'Matembwe', 'Mfriga', 'Mtwango', 'Ninga', 'Ukalawa'] },
      { name: 'Njombe Town', wards: ['Ihanga', 'Iwungilo', 'Kifanya', 'Lugenge', 'Luponde', 'Makowo', 'Matola', 'Mjimwema', 'Njombe Mjini', 'Ramadhani', 'Utalingoro', 'Uwemba', 'Yakobi'] },
      { name: 'Wanging\'ombe District', wards: ['Igima', 'Igosi', 'Igwachanya', 'Ilembula', 'Imalinyi', 'Itulahumba', 'Kidugala', 'Kijombe', 'Kipengele', 'Luduga', 'Makoga', 'Malangali', 'Mdandu', 'Saja', 'Udonja', 'Uhambule', 'Uhenga', 'Ulembwe', 'Usuka', 'Wangama', 'Wanging\'ombe'] },
    ],
  },
  {
    region: 'Pwani',
    councils: [
      { name: 'Bagamoyo District', wards: ['Dunda', 'Fukayosi', 'Kerege', 'Kiromo', 'Kisutu', 'Magomeni', 'Makurunge', 'Mapinga', 'Nianjema', 'Yombo', 'Zinga'] },
      { name: 'Chalinze District', wards: ['Bwilingu', 'Kibindu', 'Kimange', 'Kiwangwa', 'Lugoba', 'Mandera', 'Mbwewe', 'Miono', 'Mkange', 'Msata', 'Msoga', 'Pera', 'Talawanda', 'Ubenazomozi', 'Vigwaza'] },
      { name: 'Kibaha District', wards: ['Bokomnemela', 'Dutumi', 'Gwata', 'Janga', 'Kawawa', 'Kikongo', 'Kilangalanga', 'Kwala', 'Magindu', 'Mlandizi', 'Mtambani', 'Mtongani', 'Ruvu', 'Soga'] },
      { name: 'Kibaha Town', wards: ['Kibaha', 'Kongowe', 'Mailimoja', 'Mbwawa', 'Misugusugu', 'Mkuza', 'Msangani', 'Pangani', 'Picha ya Ndege', 'Sofu', 'Tangini', 'Tumbi', 'Visiga', 'Viziwaziwa'] },
      { name: 'Kibiti District', wards: ['Bungu', 'Dimani', 'Kibiti', 'Kiongoroni', 'Mahege', 'Maparoni', 'Mbuchi', 'Mchukwi', 'Mjawa', 'Mlanzi', 'Msala', 'Mtawanya', 'Mtunda', 'Mwambao', 'Ruaruke', 'Salale'] },
      { name: 'Kisarawe District', wards: ['Boga', 'Chole', 'Kazimzumbwi', 'Kibuta', 'Kiluvya', 'Kisarawe', 'Kurui', 'Mafizi', 'Maneromango', 'Marui', 'Marumbo', 'Masaki', 'Msanga', 'Msimbu', 'Mzenga', 'Vihingo', 'Vikumburu'] },
      { name: 'Mafia District', wards: ['Baleni', 'Jibondo', 'Kanga', 'Kiegeani', 'Kilindoni', 'Kirongwe', 'Miburani', 'Ndagoni'] },
      { name: 'Mkuranga District', wards: ['Beta', 'Bupu', 'Dondo', 'Kimanzichana', 'Kiparang\'anda', 'Kisegese', 'Kisiju', 'Kitomondo', 'Lukanga', 'Magawa', 'Mbezi', 'Mipeko', 'Mkamba', 'Mkuranga', 'Msonga', 'Mwandege', 'Mwarusembe', 'Njianne', 'Nyamato', 'Panzuo', 'Shungubweni', 'Tambani', 'Tengelea', 'Vianzi', 'Vikindu'] },
      { name: 'Rufiji District', wards: ['Chemchem', 'Chumbi', 'Ikwiriri', 'Kipugira', 'Mbwara', 'Mgomba', 'Mkongo', 'Mohoro', 'Mwaseni', 'Ngarambe', 'Ngorongo', 'Umwe', 'Utete'] },
    ],
  },
  {
    region: 'Rukwa',
    councils: [
      { name: 'Kalambo District', wards: ['Kanyezi', 'Kasanga', 'Katazi', 'Katete', 'Kilesha', 'Kisumba', 'Legezamwendo', 'Lyowa', 'Mambwe Nkoswe', 'Mambwekenya', 'Matai', 'Mbuluma', 'Mkali', 'Mkowe', 'Mnamba', 'Mpombwe', 'Msanzi', 'Mwazye', 'Mwimbi', 'Samazi', 'Sopa', 'Sundu', 'Ulumi'] },
      { name: 'Nkasi District', wards: ['Chala', 'Isale', 'Isunta', 'Itete', 'Kabwe', 'Kala', 'Kate', 'Kipande', 'Kipili', 'Kipundu', 'Kirando', 'Kizumbi', 'Korongwe', 'Majengo', 'Mashete', 'Mkinga', 'Mkwamba', 'Mtenga', 'Myula', 'Namanyere', 'Ninde', 'Nkandasi', 'Nkomolo', 'Ntatumbila', 'Ntuchi', 'Paramawe', 'Sintali', 'Wampembe'] },
      { name: 'Sumbawanga District', wards: ['Ikozi', 'Ilemba', 'Kaengesa', 'Kalambanzite', 'Kalumbaleza', 'Kanda', 'Kaoze', 'Kapenta', 'Kasanzama', 'Kilangawana', 'Kipeta', 'Laela', 'Lusaka', 'Lyangalile', 'Mfinga', 'Miangalua', 'Milepa', 'Mnokola', 'Mpui', 'Mpwapwa', 'Msandamuungano', 'Mtowisa', 'Muze', 'Mwadui', 'Nankanga', 'Sandulula', 'Zimba'] },
      { name: 'Sumbawanga Municipal', wards: ['Chanji', 'Izia', 'Kasense', 'Katandala', 'Kizwite', 'Lwiche', 'Mafulala', 'Majengo', 'Malangali', 'Matanga', 'Mazwi', 'Milanzi', 'Mollo', 'Momoka', 'Msua', 'Ntendo', 'Pito', 'Senga', 'Sumbawanga'] },
    ],
  },
  {
    region: 'Ruvuma',
    councils: [
      { name: 'Madaba District', wards: ['Gumbiro', 'Lituta', 'Mahanje', 'Matetereka', 'Matumbi', 'Mkongotema', 'Mtyangimbole', 'Wino'] },
      { name: 'Mbinga District', wards: ['Amani Makoro', 'Kambarage', 'Kigonsera', 'Kihangi Mahuka', 'Kipapa', 'Kipololo', 'Kitumbalomo', 'Kitura', 'Langiro', 'Linda', 'Litembo', 'Litumbandyosi', 'Lukarasi', 'Maguu', 'Mapera', 'Matiri', 'Mbuji', 'Mhongozi', 'Mikalanga', 'Mkako', 'Mkumbi', 'Mpapa', 'Muungano', 'Namswea', 'Ngima', 'Nyoni', 'Ruanda', 'Ukata', 'Wukiro'] },
      { name: 'Mbinga Town', wards: ['Bethrehemu', 'Kagugu', 'Kihungu', 'Kikolo', 'Kilimani', 'Kitanda', 'Luhuwiko', 'Lusonga', 'Luwaita', 'Masumuni', 'Matarawe', 'Mateka', 'Mbambi', 'Mbangamao', 'Mbinga Mjini', 'Mbinga Mjini B', 'Mpepai', 'Myangayanga', 'Utiri'] },
      { name: 'Namtumbo District', wards: ['Hanga', 'Kitanda', 'Ligera', 'Likuyuseka', 'Limamu', 'Lisimonji', 'Litola', 'Luchili', 'Luegu', 'Lusewa', 'Magazini', 'Mchomoro', 'Mgombasi', 'Mkongo', 'Mkongo gulioni', 'Mputa', 'Msindo', 'Msisima', 'Namabengo', 'Namtumbo', 'Rwinga'] },
      { name: 'Nyasa District', wards: ['Chiwanda', 'Kihagara', 'Kilosa', 'Kingerikiti', 'Linga', 'Liparamba', 'Lipingo', 'Lituhi', 'Liuli', 'Liwundi', 'Luhangarasi', 'Lumeme', 'Mbaha', 'Mbamba Bay', 'Mipotopoto', 'Mpepo', 'Mtipwili', 'Ngumbo', 'Tingi', 'Upolo'] },
      { name: 'Songea District', wards: ['Kilagano', 'Kizuka', 'Liganga', 'Lilahi', 'Litapwasi', 'Litisha', 'Magagula (Magagura)', 'Maposeni', 'Matimira', 'Mbinga Mhalule', 'Mpandangindo', 'Mpitimbi', 'Muhukuru', 'Ndongosi', 'Parangu', 'Peramiho'] },
      { name: 'Songea Municipal', wards: ['Bombambili', 'Lilambo', 'Lizaboni', 'Majengo', 'Matarawe', 'Mateka', 'Matogoro', 'Mfaranyaki', 'Misufini', 'Mjimwema', 'Mjini', 'Mletele', 'Msamala', 'Mshangano', 'Mwengemshindo', 'Ndilimalitembo', 'Ruhuwiko', 'Ruvuma', 'Seedfarm', 'Subira', 'Tanga'] },
      { name: 'Tunduru District', wards: ['Chiwana', 'Jakika', 'Kalulu', 'Kidodoma', 'Ligoma', 'Ligunga', 'Lukumbule', 'Majengo', 'Majimaji', 'Marumba', 'Masonya', 'Matemanga', 'Mbati', 'Mbesa', 'Mchangani', 'Mchesi', 'Mchoteka', 'Mchuluka', 'Mindu', 'Misechela', 'Mlingoti Magharibi', 'Mlingoti Mashariki', 'Mtina', 'Muhuwesi', 'Nakapanya', 'Nakayaya', 'Nalasi Magharibi', 'Nalasi Mashariki', 'Namakambale', 'Namasakata', 'Namiungo', 'Nampungu', 'Namwinyu', 'Nandembo', 'Nanjoka', 'Ngapa', 'Sisi Kwa Sisi', 'Tinginya', 'Tuwemacho'] },
    ],
  },
  {
    region: 'Shinyanga',
    councils: [
      { name: 'Kahama Municipal', wards: ['Busoka', 'Isagehe', 'Iyenze', 'Kagongwa', 'Kahama Mjini', 'Kilago', 'Kinaga', 'Majengo', 'Malunga', 'Mhongolo', 'Mhungula', 'Mondo', 'Mwendakulima', 'Ngogwa', 'Nyahanga', 'Nyandekwa', 'Nyasubi', 'Nyihogo', 'Wendele', 'Zongomera'] },
      { name: 'Kishapu District', wards: ['Bubiki', 'Bunambiyu', 'Bupigi', 'Busangwa', 'Idukilo', 'Igaga', 'Itilima', 'Kiloleli', 'Kishapu', 'Lagana', 'Maganzo', 'Masanga', 'Mondo', 'Mwadui Lohumbo', 'Mwakipoya', 'Mwamalasa', 'Mwamashele', 'Mwasubi', 'Mwataga', 'Mwaweja', 'Ndoleleji', 'Ngofila', 'Seke-Bugoro', 'Shagihilu', 'Somagedi', 'Songwa', 'Talaga', 'Uchunga', 'Ukenyenge'] },
      { name: 'Msalala District', wards: ['Bugarama', 'Bulige', 'Bulyan\'hulu', 'Busangi', 'Chela', 'Ikinda', 'Isaka', 'Jana', 'Kashishi', 'Lunguya', 'Mega', 'Mwakata', 'Mwalugulu', 'Mwanase', 'Ngaya', 'Ntobo', 'Segese', 'Shilela'] },
      { name: 'Shinyanga District', wards: ['Bukene', 'Didia', 'Ilola', 'Imesela', 'Iselamagazi', 'Itwangi', 'Lyabukande', 'Lyabusalu', 'Lyamidati', 'Masengwa', 'Mwakitolyo', 'Mwalukwa', 'Mwamala', 'Mwantini', 'Mwenge', 'Nsalala', 'Nyamalogo', 'Nyida', 'Pandagichiza', 'Puni', 'Salawe', 'Samuye', 'Solwa', 'Tinde', 'Usanda', 'Usule'] },
      { name: 'Shinyanga Municipal', wards: ['Chamaguha', 'Chibe', 'Ibadakuli', 'Ibinzamata', 'Kambarage', 'Kitangili', 'Kizumbi', 'Kolandoto', 'Lubaga', 'Masekelo', 'Mjini', 'Mwamalili', 'Mwawaza', 'Ndala', 'Ndembezi', 'Ngokolo', 'Old Shinyanga'] },
      { name: 'Ushetu District', wards: ['Bukomela', 'Bulungwa', 'Chambo', 'Chona', 'Idahina', 'Igunda', 'Igwamanoni', 'Kinamapula', 'Kisuke', 'Mapamba', 'Mpunze', 'Nyamilangano', 'Nyankende', 'Sabasabini', 'Ubagwe', 'Ukune', 'Ulewe', 'Ulowa', 'Ushetu', 'Uyogo'] },
    ],
  },
  {
    region: 'Simiyu',
    councils: [
      { name: 'Bariadi District', wards: ['Banemhi', 'Dutwa', 'Gambosi', 'Gibishi', 'Gilya', 'Ihusi', 'Ikungulyabashashi', 'Itubukilo', 'Kasoli', 'Kilalo', 'Masewa', 'Matongo', 'Mwadobana', 'Mwasubuya', 'Mwaubingi', 'Mwaumatondo', 'Ngulyati', 'Nkindwabiye', 'Nkololo', 'Sakwe', 'Sapiwi'] },
      { name: 'Bariadi Town', wards: ['Bariadi', 'Bunamhala', 'Guduwi', 'Isanga', 'Malambo', 'Mhango', 'Nyakabindi', 'Nyangokolwa', 'Sima', 'Somanda'] },
      { name: 'Busega District', wards: ['Badugu', 'Igalukilo', 'Imalamate', 'Kabita', 'Kalemela', 'Kiloleli', 'Lamadi', 'Lutubiga', 'Malili', 'Mkula', 'Mwamanyili', 'Ngasamo', 'Nyaluhande', 'Nyashimo', 'Shigala'] },
      { name: 'Itilima District', wards: ['Budalabujiga', 'Bumera', 'Chinamili', 'Ikindilo', 'Kinang\'weli', 'Lagangabilili', 'Luguru', 'Mbita', 'Mhunze', 'Migato', 'Mwalushu', 'Mwamapalala', 'Mwamtani', 'Mwaswale', 'Ndolelezi', 'Nhobora', 'Nkoma', 'Nkuyu', 'Nyamalapa', 'Sagata', 'Sawida', 'Zagayu'] },
      { name: 'Maswa District', wards: ['Badi', 'Binza', 'Buchambi', 'Budekwa', 'Bugarama', 'Busangi', 'Busilili', 'Dakama', 'Ipililo', 'Isanga', 'Jija', 'Kadoto', 'Kulimi', 'Lalago', 'Malampaka', 'Masela', 'Mataba', 'Mbaragane', 'Mpindo', 'Mwabaratulu', 'Mwabayanda', 'Mwamanenge', 'Mwamashimba', 'Mwang\'honoli', 'Ng\'wigwa', 'Nguliguli', 'Nyabubinza', 'Nyalikungu', 'Sangamwalugesha', 'Senani', 'Seng\'wa', 'Shanwa', 'Shishiyu', 'Sola', 'Sukuma', 'Zanzui'] },
      { name: 'Meatu District', wards: ['Bukundi', 'Imalaseko', 'Isengwa', 'Itinje', 'Kabondo', 'Kimali', 'Kisesa', 'Lingeka', 'Lubiga', 'Mbugayabanghya', 'Mbushi', 'Mwabuma', 'Mwabusalu', 'Mwabuzo', 'Mwakisandu', 'Mwamalole', 'Mwamanimba', 'Mwamanongu', 'Mwamishali', 'Mwandoya', 'Mwangudo', 'Mwanhuzi', 'Mwanjolo', 'Mwanyahina', 'Mwasengela', 'Ng\'hoboko', 'Nkoma', 'Sakasaka', 'Tindabuligi'] },
    ],
  },
  {
    region: 'Singida',
    councils: [
      { name: 'Ikungi District', wards: ['Dung\'unyi', 'Ighombwe', 'Iglansoni', 'Ihanja', 'Ikungi', 'Irisya', 'Isseke', 'Issuna', 'Iyumbu', 'Kikio', 'Kituntu', 'Lighwa', 'Makilawa', 'Makiungu', 'Mang\'onyi', 'Mgungira', 'Minyughe', 'Misughaa', 'Mkiwa', 'Mtunduru', 'Muhintiri', 'Mungaa', 'Mwaru', 'Ntuntu', 'Puma', 'Sepuka', 'Siuyu', 'Unyahati'] },
      { name: 'Iramba District', wards: ['Kaselya', 'Kidaru', 'Kinampanda', 'Kiomboi', 'Kisiriri', 'Kyengege', 'Maluga', 'Mbelekese', 'Mgongo', 'Mtekente', 'Mtoa', 'Mukulu', 'Ndago', 'Ndulungu', 'Ntwike', 'Old-Kiomboi', 'Shelui', 'Tulya', 'Ulemo', 'Urughu'] },
      { name: 'Itigi District', wards: ['Aghondi', 'Idodyandole', 'Ipande', 'Itigi Majengo', 'Itigi Mjini', 'Kalangali', 'Kitaraka', 'Mgandu', 'Mitundu', 'Mwamagembe', 'Rungwa', 'Sanjaranda', 'Tambukareli'] },
      { name: 'Manyoni District', wards: ['Chikola', 'Chikuyu', 'Heka', 'Isseke', 'Kintinku', 'Majiri', 'Makanda', 'Makuru', 'Makutupora', 'Manyoni', 'Maweni', 'Mkwese', 'Muhalala', 'Nkonko', 'Sanza', 'Saranda', 'Sasajila', 'Sasilo', 'Solya'] },
      { name: 'Mkalama District', wards: ['Gumanga', 'Ibaga', 'Iguguno', 'Ilunda', 'Kikhonda', 'Kinampundu', 'Kinyangiri', 'Matongo', 'Miganga', 'Mpambala', 'Msingi', 'Mwanga', 'Mwangeza', 'Nduguti', 'Nkalakala', 'Nkinto', 'Tumuli'] },
      { name: 'Singida District', wards: ['Ikhanoda', 'Ilongero', 'Itaja', 'Kijota', 'Kinyagigi', 'Kinyeto', 'Maghojoa', 'Makuro', 'Merya', 'Mgori', 'Mrama', 'Msange', 'Msisi', 'Mtinko', 'Mudida', 'Mughamo', 'Mughunga', 'Mwasauya', 'Ngimu', 'Ntonge', 'Ughandi'] },
      { name: 'Singida Municipal', wards: ['Ipembe', 'Kindai', 'Kisaki', 'Majengo', 'Mandewa', 'Minga', 'Misuna', 'Mitunduruni', 'Mtamaa', 'Mtipa', 'Mughanga', 'Mungumaji', 'Mwankoko', 'Uhamaka', 'Unyambwa', 'Unyamikumbi', 'Unyianga', 'Utemini'] },
    ],
  },
  {
    region: 'Songwe',
    councils: [
      { name: 'Ileje District', wards: ['Bupigu', 'Chitete', 'Ibaba', 'Ikinga', 'Isongole', 'Itale', 'Itumba', 'Kafule', 'Kalembo', 'Lubanda', 'Luswisi', 'Malangali', 'Mbebe', 'Mlale', 'Ndola', 'Ngulilo', 'Ngulugulu', 'Sange'] },
      { name: 'Mbozi District', wards: ['Bara', 'Halungu', 'Hasamba', 'Hasanga', 'Hezya', 'Ichenjezya', 'Idiwili', 'Igamba', 'Ihanda', 'Ilolo', 'Ipunga', 'Isalalo', 'Isansa', 'Itaka', 'Itumpi', 'Iyula', 'Kilimampimbi', 'Magamba', 'Mahenje', 'Mlangali', 'Mlowo', 'Msia', 'Nambinzo', 'Nanyala', 'Nyimbili', 'Ruanda', 'Shiwinga', 'Ukwile', 'Vwawa'] },
      { name: 'Momba District', wards: ['Chilulumo', 'Chitete', 'Ikana', 'Ivuna', 'Kamsamba', 'Kapele', 'Mkomba', 'Mkulwe', 'Mpapa', 'Msangano', 'Myunga', 'Ndalambo', 'Nkangamo', 'Nzoka'] },
      { name: 'Songwe District', wards: ['Chang\'ombe', 'Galula', 'Gua', 'Ifwenkenya', 'Kanga', 'Kapalala', 'Magamba', 'Manda', 'Mbangala', 'Mbuyuni', 'Mkwajuni', 'Mpona', 'Mwambani', 'Namkukwe', 'Ngwala', 'Saza', 'Totowe', 'Udinde'] },
      { name: 'Tunduma Town', wards: ['Chapwa', 'Chipaka', 'Chiwezi', 'Kaloleni', 'Katete', 'Majengo', 'Makambini', 'Maporomoko', 'Mpande', 'Mpemba', 'Muungano', 'Mwakakati', 'Sogea', 'Tunduma', 'Uwanjani'] },
    ],
  },
  {
    region: 'Tabora',
    councils: [
      { name: 'Igunga District', wards: ['Bukoko', 'Chabutwa', 'Chomachankola', 'Iborogelo', 'Igoweko', 'Igunga', 'Igurubi', 'Isakamaliwa', 'Itumba', 'Itunduru', 'Kining\'inila', 'Kinungu', 'Kitangili', 'Lugubu', 'Mbutu', 'Mtunguru', 'Mwamakona', 'Mwamala', 'Mwamashiga', 'Mwamashimba', 'Mwashikumbili', 'Mwisi', 'Nanga', 'Ndembezi', 'Ngulu', 'Nguvumoja', 'Nkinga', 'Ntobo', 'Nyandekwa', 'Simbo', 'Sungwizi', 'Tambalale', 'Ugaka', 'Uswaya', 'Ziba'] },
      { name: 'Kaliua District', wards: ['Ichemba', 'Igagala', 'Igombemkulu', 'Igwisi', 'Ilege', 'Kaliua', 'Kamsekwa', 'Kanoge', 'Kashishi', 'Kazaroho', 'Kona Nne', 'Makingi', 'Milambo', 'Mkindo', 'Mwongozo', 'Nhwande', 'Sasu', 'Seleli', 'Silambo', 'Ufukutwa', 'Ugunga', 'Ukumbi Siganga', 'Usenye', 'Ushokola', 'Usimba', 'Usinge', 'Uyowa', 'Zugimlole'] },
      { name: 'Nzega District', wards: ['Budushi', 'Bukene', 'Igusule', 'Ikindwa', 'Isagenhe', 'Isanzu', 'Itobo', 'Kahamanhalanga', 'Karitu', 'Kasela', 'Lusu', 'Magengati', 'Mambali', 'Mbagwa', 'Mbutu', 'Milambo Itobo', 'Mizibaziba', 'Mogwa', 'Muhugi', 'Mwakashanhala', 'Mwamala', 'Mwangoye', 'Mwantundu', 'Mwasala', 'Nata', 'Ndala', 'Nkiniziwa', 'Puge', 'Semembela', 'Shigamba', 'Sigili', 'Tongi', 'Uduka', 'Ugembe', 'Utwigu', 'Wela'] },
      { name: 'Nzega Town', wards: ['Ijanija', 'Itilo', 'Kitangili', 'Mbogwe', 'Miguwa', 'Mwanzoli', 'Nzega Mjini Magharibi', 'Nzega Mjini Mashariki', 'Nzega Ndogo', 'Uchama'] },
      { name: 'Sikonge District', wards: ['Chabutwa', 'Igigwa', 'Ipole', 'Kiloleli', 'Kiloli', 'Kilumbi', 'Kipanga', 'Kipili', 'Kisanga', 'Kitunda', 'Misheni', 'Mkolye', 'Mole', 'Mpombwe', 'Ngoywa', 'Nyahua', 'Pangale', 'Sikonge', 'Tutuo', 'Usunga'] },
      { name: 'Tabora Municipal', wards: ['Chemchem', 'Cheyo', 'Gongoni', 'Ifucha', 'Ikomwa', 'Ipuli', 'Isevya', 'Itetemia', 'Itonjanda', 'Kabila', 'Kakola', 'Kalunde', 'Kanyenye', 'Kidongochekundu', 'Kiloleni', 'Kitete', 'Malolo', 'Mapambano', 'Mbugani', 'Misha', 'Mpela', 'Mtendeni', 'Mwinyi', 'Ndevelwa', 'Ng\'ambo', 'Ntalikwa', 'Tambuka-Reli', 'Tumbi', 'Uyui'] },
      { name: 'Urambo District', wards: ['Imalamakoye', 'Itundu', 'Kapilula', 'Kasisi', 'Kiloleni', 'Kiyungi', 'Mchikichini', 'Muungano', 'Nsenda', 'Songambele', 'Ugalla', 'Ukondamoyo', 'Urambo', 'Usisya', 'Ussoke', 'Uyogo', 'Uyumbu', 'Vumilia'] },
      { name: 'Uyui District', wards: ['Bukumbi', 'Goweko', 'Ibelamilundi', 'Ibiri', 'Igalula', 'Igulungu', 'Ikongolo', 'Ilolangulu', 'Isikizya', 'Isila', 'Kalola', 'Kigwa', 'Kizengi', 'Loya', 'Lutende', 'Mabama', 'Magiri', 'Makazi', 'Miswaki', 'Miyenze', 'Mmale', 'Ndono', 'Nsimbo', 'Nsololo', 'Nzubuka', 'Shitage', 'Tura', 'Ufuluma', 'Upuge', 'Usagari'] },
    ],
  },
  {
    region: 'Tanga',
    councils: [
      { name: 'Bumbuli District', wards: ['Baga', 'Bumbuli', 'Dule B', 'Funta', 'Kisiwani', 'Kwemkomole', 'Mahezangulu', 'Mamba', 'Mayo', 'Mbuzii', 'Mgwashi', 'Milingano', 'Mponde', 'Nkongoi', 'Soni', 'Tamota', 'Usambara', 'Vuga'] },
      { name: 'Handeni District', wards: ['Kabuku', 'Kabuku Ndani', 'Kang\'ata', 'Kitumbi', 'Kiva', 'Komkonga', 'Kwachaga', 'Kwaluguru', 'Kwamatuku', 'Kwamgwe', 'Kwamsisi', 'Kwankonje', 'Kwasunga', 'Kwedizinga', 'Mazingara', 'Mgambo', 'Misima', 'Mkata', 'Ndolwa', 'Segera', 'Sindeni'] },
      { name: 'Handeni Town', wards: ['Chanika', 'Kideleko', 'Konje', 'Kwamagome', 'Kwediyamba', 'Kwenjugo', 'Mabanda', 'Malezi', 'Mdoe', 'Mlimani', 'Msasa', 'Vibaoni'] },
      { name: 'Kilindi District', wards: ['Bokwa', 'Jaila', 'Kibirashi', 'Kikunde', 'Kilindi', 'Kilwa', 'Kimbe', 'Kisangasa', 'Kwediboma', 'Kwekivu', 'Lwande', 'Mabalanga', 'Masagalu', 'Mkindi', 'Msanja', 'Mvungwe', 'Negero', 'Pagwi', 'Saunyi', 'Songe', 'Tunguli'] },
      { name: 'Korogwe District', wards: ['Bungu', 'Chekelei', 'Dindira', 'Foroforo', 'Hale', 'Kalalani', 'Kerenge', 'Kizara', 'Kwagunda', 'Kwashemshi', 'Lewa', 'Lutindi', 'Magamba Kwalukonge', 'Magila Gereza', 'Magoma', 'Makumba', 'Makuyuni', 'Mashewa', 'Mazinde', 'Mgwashi', 'Mkalamo', 'Mkomazi', 'Mkumbara', 'Mlungui', 'Mnyuzi', 'Mombo', 'Mpale', 'Mswaha', 'Vugiri'] },
      { name: 'Korogwe Town', wards: ['Bagamoyo', 'Kilole', 'Kwamndolwa', 'Kwamsisi', 'Magunga', 'Majengo', 'Manundu', 'Masuguru', 'Mgombezi', 'Mtonga', 'Old Korogwe'] },
      { name: 'Lushoto District', wards: ['Dule M', 'Gare', 'Hemtoye', 'Kilole', 'Kwai', 'Kwekanga', 'Kwemashai', 'Kwemshasha', 'Lukozi', 'Lunguza', 'Lushoto', 'Magamba', 'Makanya', 'Malibwi', 'Malindi', 'Manolo', 'Mbaramo', 'Mbaru', 'Mbwei', 'Migambo', 'Mlalo', 'Mlola', 'Mnazi', 'Mng\'aro', 'Mtae', 'Mwangoi', 'Ngulwi', 'Ngwelo', 'Rangwi', 'Shagayu', 'Shume', 'Sunga', 'Ubiri'] },
      { name: 'Mkinga District', wards: ['Boma', 'Bosha', 'Bwiti', 'Daluni', 'Doda', 'Duga', 'Gombero', 'Kigongoi Magharibi', 'Kigongoi Mashariki', 'Kwale', 'Manza', 'Mapatano', 'Maramba', 'Mayomboni', 'Mhinduro', 'Mkinga', 'Mnyenzani', 'Moa', 'Mtimbwani', 'Mwakijembe', 'Parungu Kasera', 'Sigaya'] },
      { name: 'Muheza District', wards: ['Amani', 'Bwembwera', 'Genge', 'Kicheba', 'Kigombe', 'Kilulu', 'Kisiwani', 'Kwabada', 'Kwafungo', 'Kwakifua', 'Kwemingoji', 'Kwemkabala', 'Kwezitu', 'Lusanga', 'Magila', 'Magoroto', 'Majengo', 'Makole', 'Masuguru', 'Mbaramo', 'Mbomole', 'Mhamba', 'Misalai', 'Misozwe', 'Mkuzi', 'Mlingano', 'Mpapayu', 'Mtindiro', 'Ngomeni', 'Nkumba', 'Pande Darajani', 'Potwe', 'Songa', 'Tanganyika', 'Tingeni', 'Tongwe', 'Zirai'] },
      { name: 'Pangani District', wards: ['Bushiri', 'Bweni', 'Kimang\'a', 'Kipumbwi', 'Madanga', 'Masaika', 'Mikinguni', 'Mkalamo', 'Mkwaja', 'Mwera', 'Pangani Magharibi', 'Pangani Mashariki', 'Tungamaa', 'Ubangaa'] },
      { name: 'Tanga City', wards: ['Central', 'Chongoleani', 'Chumbageni', 'Duga', 'Kiomoni', 'Kirare', 'Mabawa', 'Mabokweni', 'Magaoni', 'Majengo', 'Makorora', 'Marungu', 'Masiwani', 'Maweni', 'Mnyanjani', 'Msambweni', 'Mwanzange', 'Mzingani', 'Mzizima', 'Ngamiani Kaskazini', 'Ngamiani Kati', 'Ngamiani Kusini', 'Nguvumali', 'Pongwe', 'Tangasisi', 'Tongoni', 'Usagara'] },
    ],
  },
];

/** 31 regions, 195 councils, 4344 wards and shehia. */

/** The councils of a region, or none for a region not on the list (blank, or an older typed value). */
export function councilsOf(region: string): readonly Council[] {
  return TANZANIA_REGIONS.find((r) => r.region === region)?.councils ?? [];
}

/** The wards (shehia in Zanzibar) of a council in a region, or none when either is not on the list. */
export function wardsOf(region: string, council: string): readonly string[] {
  return councilsOf(region).find((c) => c.name === council)?.wards ?? [];
}

/** Whether a region is one of the listed ones -- false for blank and for an older typed value. */
export function isListedRegion(region: string): boolean {
  return TANZANIA_REGIONS.some((r) => r.region === region);
}
