package tomoaki.WebScraper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.DomNode;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import tomoaki.courseClasses.Course;
import tomoaki.courseClasses.DayOfWeek;
import tomoaki.courseClasses.Hours;

public class Scraper {

	private List<Course> courses;
	
	public Scraper() {
		this("http://www.westfield.ma.edu/offices/registrar/master-schedule");
	}
	
	public List<Course> getCourses() {
		return courses;
	}
	
	public void setCourses(List<Course> courses) {
		this.courses = courses;
	}
	
	public Scraper(String URL) {
		long start = System.currentTimeMillis();
		courses = new ArrayList<Course>();
		
		WebClient client = new WebClient();
		client.getOptions().setCssEnabled(false);
		client.getOptions().setJavaScriptEnabled(false);

		try{
			HtmlPage page = client.getPage(URL);
			// In each courseTable, there are list of courses
			List<HtmlElement> trs = page.getByXPath("//tr");
			for(HtmlElement tr : trs){
				List<HtmlElement> tds = tr.getByXPath("td");
				if(tds.size() < 8) continue;
				
				//extract information to instantiate Course object
				Course course = new Course();
				scrapeFirstCell(course, tds.get(0));
				scrapeSecondCell(course, tds.get(1));
				scrapeFourthCell(course, tds.get(3));
				scrapeFifthCell(course, tds.get(4));
				scrapeSixthCell(course, tds.get(5));
				scrapeSevenCell(course, tds.get(6));
				scrapeEighthCell(course, tds.get(7));
				courses.add(course);
			}
			
			// detect isCancelled
			for(Course course : courses){
				if(course.getRoom().length() == 0 && course.getFaculty().equals("STAFF") && course.getHoursOfDay().size() == 0){
					course.setIsCancelled(true);
				}
			}
			
			//check anything
			HashSet<String> subjects = new HashSet();
			for(Course course : courses){
				if(!course.getIsLabCourse()){
					String subject = course.getCourseCRN().split(" ")[0];
					course.setSubject(subject);
					subjects.add(subject);
				}
			}
			
			String[] subs = new String[subjects.size()];
			
			Iterator it = subjects.iterator();
			int i=0;
			while(it.hasNext()){
				subs[i++] = (String) it.next();
			}
			
			Arrays.sort(subs, (a,b) -> a.compareTo(b));
			for(String sub : subs){
				System.out.println(sub);
			}
			
			System.out.println("Time: " + ((System.currentTimeMillis() - start) / 1000) + " second");
		}catch(Exception e){
			e.printStackTrace();
		}
	}
	
	private void scrapeEighthCell(Course course, HtmlElement htmlElement) {
		String[] cores = htmlElement.getTextContent().split("/");
		for(String core : cores){
			course.addCore(core);
		}
	}

	private void scrapeSevenCell(Course course, HtmlElement htmlElement) {
		String content = htmlElement.getTextContent();
		double credit;
		try{
			credit = Double.parseDouble(content);
		}catch(NumberFormatException e){
			credit = 0;
		}
		course.setCredit(credit);
	}

	private void scrapeSixthCell(Course course, HtmlElement htmlElement) {
		String content = htmlElement.getTextContent();
		course.setRoom(content);
	}
	
	/**
	 * TODO: Detect whether content hours is in complex form or simple form
	 * Remove unneeded content: <strong>Hybrid(...)</>
	 *                          <strong>First/Second(...)</>
	 *                          if child node exist
	 */
	private void scrapeFifthCell(Course course, HtmlElement htmlElement) {
		String content = htmlElement.getTextContent();
		
		DomNode child = htmlElement.getLastElementChild();
		String childTagName = null;
		if(child != null){
			String timeContent = child.getTextContent().trim();
			course.setTimeContent(timeContent);
			childTagName = child.getNodeName();
			htmlElement.removeChild(childTagName,0);
			content = htmlElement.getTextContent();
		}
		
		String[] timeCells = content.split("\\s+");
		if(content == null || content.length() == 0 || timeCells.length < 4){
			return;
		}
		EnumMap<DayOfWeek, List<Hours>> hoursOfDay = new EnumMap<>(DayOfWeek.class);
		
		// if third(0-base) timeCell length in timeCells is larger than 2, it is complex form
		if(timeCells[3].length() > 2){
			extractHoursComplex(course, hoursOfDay, content);
			course.setHoursOfDay(hoursOfDay);
		}else{
			if(timeCells.length < 10)
				extractHoursSimple(course, hoursOfDay, content);
			course.setHoursOfDay(hoursOfDay);
		}
	}
	
	/**
	 * TODO: Extract/separate time interval in a form of Simple Form
	 *
	 * @param hoursOfDay
	 * @param timeAsText Input Complex Format: "R 09:30 AM-10:30 PMMWF 11:45 AM-12:45 PM"
	 *
	 *                   Input Simple Format:  "R 09:30 AM-10:30 PM"
	 */
	private void extractHoursComplex(Course course, EnumMap<DayOfWeek, List<Hours>> hoursOfDay, String timeAsText) {
		String[] timeCells = timeAsText.split("\\s+");
		
		// timeCells has two(or more) time interval.
		// ex: T 03:45 PM-05:45 PMMW 09:20 AM-10:10 AMF03:45 PM-05:45PM
		// put @ between in every 3rd(0-base) cell to separate time interval properly
		for(int i=3; i<timeCells.length; i+=3){
			String endOfFirstTimeInterval = timeCells[i].substring(0,2);
			String startOfSecondTimeInterval = timeCells[i].substring(2);
			timeCells[i] = endOfFirstTimeInterval + "@" + startOfSecondTimeInterval;
		}
		
		// simply convert timeCells to textFormat
		//  FROM: [T][03:45][PM-05:45][PM@MW][09:20]....[PM-05:45][PM]
		//  TO  :  T 03:45 PM-05:45 PM@MW 09:20 AM-10:10 AM@F03:45 PM-05:45 PM
		StringBuilder intervalTimeAsTextSB = new StringBuilder();
		for(String timeCell : timeCells){
			intervalTimeAsTextSB.append(timeCell);
			intervalTimeAsTextSB.append(" ");
		}
		
		// T 03:45 PM-05:45 PM@MW 09:20 AM-10:10 AM@F03:45 PM-05:45 PM
		// split by single/multiple spaces, and then extract as simple
		String multipleTimeIntervalAsText = intervalTimeAsTextSB.toString().trim();
		String[] timeIntervals = multipleTimeIntervalAsText.split("@");
		for(String timeInterval : timeIntervals){
			extractHoursSimple(course, hoursOfDay, timeInterval);
		}
	}
	
	private void extractHoursSimple(Course course, EnumMap<DayOfWeek, List<Hours>> hoursOfDay, String timeInterval) {
		setTimeContentInCourse(course, timeInterval);
		
		String[] timeBox = timeInterval.split("\\s+");
		//"TR" -> 'T' 'R'
		char[] days = timeBox[0].toCharArray();
		
		// "PM-02:00" -> "PM" "02:00"
		String[] timeTag_endTime = timeBox[2].split("-");
		
		//concat times // might change it to StringBuilder for time complexity
		String startTime = timeBox[1] + timeTag_endTime[0].toLowerCase();
		String endTime = timeTag_endTime[1] + timeBox[timeBox.length-1].toLowerCase();
		String interval = startTime + "-" + endTime;
		try{
			Hours hours = new Hours(interval);
			for(char day : days){
				switch(day){
					case 'M':
						handleChangesOnHoursMap(DayOfWeek.MONDAY, hoursOfDay, hours);
						break;
						
					case 'T':
						handleChangesOnHoursMap(DayOfWeek.TUESDAY, hoursOfDay, hours);
						break;
						
					case 'W':
						handleChangesOnHoursMap(DayOfWeek.WEDNESDAY, hoursOfDay, hours);
						break;
						
					case 'R':
						handleChangesOnHoursMap(DayOfWeek.THURSDAY, hoursOfDay, hours);
						break;
						
					case 'F':
						handleChangesOnHoursMap(DayOfWeek.FRIDAY, hoursOfDay, hours);
						break;
						
					case 'S':
						handleChangesOnHoursMap(DayOfWeek.SATURDAY, hoursOfDay, hours);
						break;
				}
			}
		}catch(Exception e){
			System.out.println(interval);
			e.printStackTrace();
		}
	}
	
	public void handleChangesOnHoursMap(DayOfWeek day, EnumMap<DayOfWeek,List<Hours>> hoursOfDay, Hours hours) {
		if(hoursOfDay.get(day) == null){
			List<Hours> hoursList = new ArrayList();
			hoursList.add(hours);
			hoursOfDay.put(day, hoursList);
		}else{
			hoursOfDay.get(day).add(hours);
		}
	}
	
	private void setTimeContentInCourse(Course course, String timeInterval) {
		// simply set timeInterval in course attributes timeContent
		String newTimeInterval = (timeInterval.charAt(timeInterval.length()-1) == '\n') ? timeInterval.substring(0,timeInterval.length()-1) : timeInterval;
		if(course.getTimeContent() == null || course.getTimeContent().length() == 0 ||course.getTimeContent().charAt(course.getTimeContent().length()-1) == '\n'){
			course.setTimeContent(newTimeInterval);
		}else{
			course.setTimeContent(course.getTimeContent() + "\n" + newTimeInterval);
		}
	}
	
	private void scrapeFourthCell(Course course, HtmlElement htmlElement) {
		String faculty = htmlElement.getTextContent().trim();
		course.setFaculty(faculty);
	}

	private void scrapeSecondCell(Course course, HtmlElement htmlElement) {
		String title = htmlElement.getTextContent();
		DomNode anchor = htmlElement.getFirstByXPath("a");
		if(anchor != null){
			title = anchor.getTextContent();
		}
		course.setTitle(title);
		
		// extract course description
		List<HtmlElement> div = htmlElement.getByXPath("div");
		if(div != null && div.size() != 0){
			DomNode divChild = div.get(0).getFirstByXPath("div[@class='h2']");
			String description = divChild.getTextContent().trim();
			if(description != null && description.length() > 0){
				course.setCourseDescription(description);
			}else{
				course.setCourseDescription("***No Available Description***");
			}
		}
		
		// detect if it is Lab course
		String[] words = title.split(" ");
		if(words != null && words.length != 0){
			if(words[words.length-1].toLowerCase().equals("lab")){
				course.serIsLabCourse(true);
			}else{
				course.serIsLabCourse(false);
			}
		}else{
			course.serIsLabCourse(false);
		}
	}

	private void scrapeFirstCell(Course course, HtmlElement htmlElement) {
		String content = htmlElement.getTextContent();
		course.setCourseCRN(content);
	}
	
	public void writeToJSON(String jsonFileName) {
		// write to JSON
		File menuFile = new File(jsonFileName);
		ObjectMapper mapper = new ObjectMapper();
		try {
			mapper.writerFor(new TypeReference<List<Course>>() {
			}).withDefaultPrettyPrinter()
				.writeValue(menuFile, courses);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public static void main(String[] args) {
		Scraper scraper = new Scraper();
		List<Course> courses = scraper.getCourses();
		scraper.writeToJSON("current-semester.json");
	}
}
