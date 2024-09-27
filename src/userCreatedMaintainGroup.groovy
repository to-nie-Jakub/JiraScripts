import org.apache.log4j.Level
import org.apache.log4j.Logger
import com.atlassian.mail.Email
import com.atlassian.jira.user.util.UserManager
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.user.ApplicationUsers
import com.atlassian.crowd.event.user.UserCreatedEvent
import com.atlassian.crowd.event.user.AutoUserCreatedEvent
import com.atlassian.crowd.event.user.AutoUserUpdatedEvent
import com.atlassian.crowd.event.user.UserCreatedFromDirectorySynchronisationEvent
import com.atlassian.crowd.event.user.UserCredentialUpdatedEvent
import com.atlassian.crowd.event.user.UserEditedEvent
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.crowd.model.user.ImmutableUser



ApplicationUser getAppUserFromEvent(def event){
    def userManager = ComponentAccessor.getComponent(UserManager)
    return userManager.getUserByKey(ApplicationUsers.getKeyFor(event.getUser()))
}

void sendMail(String body){
    log.warn(body)
    return;
    try{
        def mailServer = ComponentAccessor.getMailServerManager().getDefaultSMTPMailServer()
        def mail = new Email("iss-support@aety.io");
        mail.setBody(body);
        mail.setSubject("jira.issworld.com: Error in listener mapping UPN to Group")
        mailServer.send(mail)
    } catch(Exception e){
        log.error("Could not send mail", e)
        throw e
    }
}

boolean isInternalEmployee(ApplicationUser user) {
    return !user.getUsername().endsWith("@external.issworld.com")
}

String groupName = "jira-internal-employees"
def log = Logger.getLogger("io.aety.scriptrunner.upntointernalgroupmapper")

log.setLevel(Level.WARN)

ApplicationUser appUser = getAppUserFromEvent(event)
if (!appUser) {
    def message = "event did not contain any user: " + event
    sendMail(message)
    throw new Exception(message)
}

if(isInternalEmployee(appUser)) {
    def groupManager = ComponentAccessor.getGroupManager()
    try{
        def group = groupManager.getGroup(groupName)
        groupManager.addUserToGroup(appUser,group)
    } catch (Exception e){
        String body = "Could not add user " + appUser.getUsername() + " to group " + groupName
        sendMail(body)
        throw e;
    }
}

