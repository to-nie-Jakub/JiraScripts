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
boolean isInternalEmployee(ApplicationUser user) {
    if (user.username == null) {
        return true // Handle null case as needed
    }
    String regex = /^.+@(?!(.*external.*)).{0,16}\.issworld\.com$/
    return (user.username =~ regex)
}

String groupName = "jira-internal-employees"
String externalGroupName = "jira-external-employees"

ApplicationUser appUser = getAppUserFromEvent(event)
if (!appUser) {
    def message = "event did not contain any user: " + event
    throw new Exception(message)
}

if(isInternalEmployee(appUser)) {
    def groupManager = ComponentAccessor.getGroupManager()
    try{
        def group = groupManager.getGroup(groupName)
        groupManager.addUserToGroup(appUser,group)
    } catch (Exception e){
        String body = "Could not add user " + appUser.getUsername() + " to group " + groupName
        throw e;
    }
} else if (appUser.getUsername().endsWith("@external.issworld.com")){
    def groupManager = ComponentAccessor.getGroupManager()
    try{
        def group = groupManager.getGroup(externalGroupName)
        groupManager.addUserToGroup(appUser,group)
    } catch (Exception e){
        String body = "Could not add user " + appUser.getUsername() + " to group " + groupName
        throw e;
    }
}