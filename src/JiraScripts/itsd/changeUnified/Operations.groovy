package JiraScripts.itsd.changeUnified

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.logging.Logger

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.customfields.manager.OptionsManager
import com.atlassian.jira.issue.changehistory.ChangeHistoryManager
import com.atlassian.jira.issue.changehistory.ChangeHistoryItem
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.issue.context.IssueContext
import com.atlassian.jira.issue.context.IssueContextImpl
import com.atlassian.jira.project.ProjectManager
import com.atlassian.jira.config.IssueTypeManager
import com.atlassian.jira.issue.issuetype.IssueType
import com.atlassian.jira.project.Project
import com.atlassian.jira.issue.fields.config.FieldConfig
import com.atlassian.jira.issue.customfields.option.Options
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.security.groups.GroupManager
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.bc.issue.IssueService.CreateValidationResult
import com.atlassian.jira.bc.issue.IssueService.IssueResult
import com.atlassian.jira.issue.fields.config.manager.PrioritySchemeManager
import com.atlassian.jira.config.ConstantsManager
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.config.SubTaskManager
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.user.util.UserManager

import com.riadalabs.jira.plugins.insight.services.model.ObjectBean

import com.onresolve.jira.groovy.user.FormField
import com.onresolve.jira.groovy.user.FieldBehaviours
import com.onresolve.scriptrunner.runner.customisers.WithPlugin

import io.aety.Util
import io.aety.jira.IssueOperations
import io.aety.jira.insight.Insight
import static com.issworld.change.Constants.FieldSetting

@WithPlugin("com.riadalabs.jira.plugins.insight")

class Operations {
    private static final Logger logger = Logger.getLogger(Operations.class.getName())

    /* Setting up the Jira API */
    private static final CustomFieldManager customFieldManager = ComponentAccessor.getCustomFieldManager()
    private static final IssueManager issueManager = ComponentAccessor.getIssueManager()
    private static final IssueService issueService = ComponentAccessor.getIssueService()
    private static final OptionsManager optionsManager = ComponentAccessor.getOptionsManager()
    private static final ChangeHistoryManager changeHistoryManager = ComponentAccessor.getChangeHistoryManager()
    private static final ProjectManager projectManager = ComponentAccessor.getProjectManager()
    private static final IssueTypeManager issueTypeManager = ComponentAccessor.getComponent(IssueTypeManager)
    private static final GroupManager groupManager = ComponentAccessor.getGroupManager()
    private static final PrioritySchemeManager prioritySchemeManager = ComponentAccessor.getComponent(PrioritySchemeManager)
    private static final ConstantsManager constantsManager = ComponentAccessor.getConstantsManager()
    private static final SubTaskManager subTaskManager = ComponentAccessor.getSubTaskManager()
    private static final UserManager userManager = ComponentAccessor.getUserManager()

    /* Utility functions with the sole purpose of debugging when the state of fields changes */
    /*private static void setReadOnly(FormField field, boolean readOnly) {

    }*/

    private static String getFieldId(FormField field) {
        String string = field.toString()
        string = string.substring(string.indexOf("ID: ") + 4)
        //logger.severe("getFieldId(): 1 ${string}")
        string = string.substring(0, string.indexOf(","))
        //logger.severe("getFieldId(): 2 ${string}")
        return string
    }

    /**
     * With the amount of fields involved and the number of situations that will change them, it is nice to have
     * a place where all changes of a specific field can be logged.
     * This is why this method is used to change field values.
     */
    private static void setFormValue(FormField field, Object value) {
        String fieldId = getFieldId(field)
        CustomField customField = customFieldManager.getCustomFieldObject(fieldId)
        if(customField) {
            String name = customField.getUntranslatedName()
            if(name == "Change Process Manager") {
                logger.severe("setFormValue(): field.getName() = ${name}, value = ${value}, field = ${field}")
            }
        }
        field.setFormValue(value)
    }

    /**
     * Returns true if <code>issue</code> has been in status <code>status</code> AND left it again.
     */
    public static boolean hasIssueLeftStatus(Issue issue, String status) {
        changeHistoryManager.getAllChangeItems(issue).findAll{ChangeHistoryItem item -> item.getField() == "status" && item.getFroms().values().contains(status)}
    }

    /**
     * called by Behaviour initialisation
     */
    public static void initialiseForm(FieldBehaviours fieldBehaviours) {
        logger.severe("initialiseForm()")
        showHideFieldsChangeType(fieldBehaviours)

        IssueOperations.getFormFieldByName(fieldBehaviours, "Change subtype").setDescription("""
			The 'Change subtype' is calculated based on the other fields in this form. They must be filled out first.<br/>
			A lead time has to be added to the targeted Implementation Start Date.<br/>
			<style>table, th, td {border: 1px solid white;border-collapse: collapse;} </style>
			<table style="width:100%">
			<tr><th style="color:black;text-align:left">Change Subtype</th><th style="color:black;text-align:left">Lead Time</th></tr>
			<tr><td>Minor Change</td><td>1 day lead time</td></tr>
			<tr><td>Significant Change</td><td>3 days lead time</td></tr>
			<tr><td>Major Change</td><td>5 days lead time</td></tr>
			</table>""")

        IssueOperations.getFormFieldByName(fieldBehaviours, "CI-Services").setLabel("Services")
        IssueOperations.getFormFieldByName(fieldBehaviours, "CI-Class").setLabel("Service Package")
        IssueOperations.getFormFieldByName(fieldBehaviours, "CI-Type").setLabel("CI Type")
        IssueOperations.getFormFieldByName(fieldBehaviours, "CI-Component").setLabel("Service Component")


        IssueOperations.getFormFieldByName(fieldBehaviours, "Summary").setDescription("Describe shortly the planned activity.")
        IssueOperations.getFormFieldByName(fieldBehaviours, "Description").setDescription("Describe in more details what need to be done.")
        IssueOperations.getFormFieldByName(fieldBehaviours, "Country").setDescription("Select your country.")
        IssueOperations.getFormFieldByName(fieldBehaviours, "Resolver Group").setDescription("A team that will implement this change.")
        IssueOperations.getFormFieldByName(fieldBehaviours, "Additional Resolver Groups").setDescription("A team that will implement this change.")

        FormField changeProcessManagerField = IssueOperations.getFormFieldByName(fieldBehaviours, "Change Process Manager")
        String changeProcessManagerName = userManager.getUserByName("anna.orczykowska@group.issworld.com").getName()
        // setFormValue(changeProcessManagerField, [changeProcessManagerName])
        String currentUserName = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser().getName()

        if(fieldBehaviours.underlyingIssue) {
            // An existing issue is being edited
            logger.severe("initialiseForm(): existing issue")

            String status = fieldBehaviours.underlyingIssue.getStatus().getName()
            logger.severe("initialiseForm(): currentUserName = ${currentUserName}, status = ${status}")
            if(status != "Planning" && currentUserName != changeProcessManagerName) {
                lockFields(fieldBehaviours)
            }
        } else {
            // New issue being created
            IssueOperations.getFormFieldByName(fieldBehaviours, "Manager on duty").setReadOnly(true)
        }
    }

    /**
     * Change Process Manager
     */
    public static void changeProcessManagerChanged(FieldBehaviours fieldBehaviours) {
        String currentUserName = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser().getName()
        String changeProcessManagers = IssueOperations.getFormFieldByName(fieldBehaviours, "Change Process Manager").getValue() as String

        logger.severe("changeProcessManagerChanged(): currentUserName: ${currentUserName}, changeProcessManagers: ${changeProcessManagers}")

        if(changeProcessManagers.contains(currentUserName)) {
            IssueOperations.getFormFieldByName(fieldBehaviours, "Change subtype").setReadOnly(false)
            IssueOperations.getFormFieldByName(fieldBehaviours, "Change risk").setReadOnly(false)
            IssueOperations.getFormFieldByName(fieldBehaviours, "Change Impact").setReadOnly(false)
        } else {
            IssueOperations.getFormFieldByName(fieldBehaviours, "Change subtype").setReadOnly(true)
            IssueOperations.getFormFieldByName(fieldBehaviours, "Change risk").setReadOnly(true)
            IssueOperations.getFormFieldByName(fieldBehaviours, "Change Impact").setReadOnly(true)
        }
    }

    /**
     * Change type
     */
    public static void changeTypeChanged(FieldBehaviours fieldBehaviours) {
        showHideFieldsChangeType(fieldBehaviours)
        setChangeCategoryOptions(fieldBehaviours)
        setSummary(fieldBehaviours)
        calculateAllFields(fieldBehaviours)
    }

    /**
     * Is downtime required?
     */
    public static void isDownTimeRequiredChanged(FieldBehaviours fieldBehaviours) {
        showHideFields(fieldBehaviours)
        calculateAllFields(fieldBehaviours)
    }

    /**
     * Is downtime required for fallback?
     * Already implemented?
     */
    public static void showHideFields(FieldBehaviours fieldBehaviours) {
        String downtimeRequired = IssueOperations.getFormFieldByName(fieldBehaviours, "Is downtime required?").getValue()
        String downtimeRequiredForFallback = IssueOperations.getFormFieldByName(fieldBehaviours, "Is downtime required for fallback?").getValue()
        String alreadyImplemented = IssueOperations.getFormFieldByName(fieldBehaviours, "Already implemented?").getValue()
        String changeType = IssueOperations.getFormFieldByName(fieldBehaviours, "Change type").getValue()

        if(downtimeRequired == "Yes" && changeType != Constants.STANDARD_CHANGE && alreadyImplemented != "Yes") {
            IssueOperations.getFormFieldByName(fieldBehaviours, "Downtime start").setHidden(false).setRequired(true)
            IssueOperations.getFormFieldByName(fieldBehaviours, "Downtime end").setHidden(false).setRequired(true)
        } else {
            IssueOperations.getFormFieldByName(fieldBehaviours, "Downtime start").setHidden(true).setRequired(false)
            IssueOperations.getFormFieldByName(fieldBehaviours, "Downtime end").setHidden(true).setRequired(false)
        }

        if(downtimeRequiredForFallback == "Yes" && changeType != Constants.STANDARD_CHANGE && alreadyImplemented != "Yes") {
            IssueOperations.getFormFieldByName(fieldBehaviours, "Downtime for fallback").setHidden(false).setRequired(true)
        } else {
            IssueOperations.getFormFieldByName(fieldBehaviours, "Downtime for fallback").setHidden(true).setRequired(false)
        }

        if(changeType == Constants.EMERGENCY_CHANGE) {
            if(alreadyImplemented == "Yes") {
                IssueOperations.getFormFieldByName(fieldBehaviours, "Fallback plan").setHidden(true).setRequired(false)
                IssueOperations.getFormFieldByName(fieldBehaviours, "Is downtime required for fallback?").setHidden(true).setRequired(false)
                IssueOperations.getFormFieldByName(fieldBehaviours, "Was Fallback Plan tested?").setHidden(true).setRequired(false)
                IssueOperations.getFormFieldByName(fieldBehaviours, "Peer Reviewer").setHidden(true).setRequired(false)
            } else {
                IssueOperations.getFormFieldByName(fieldBehaviours, "Fallback plan").setHidden(false).setRequired(true)
                IssueOperations.getFormFieldByName(fieldBehaviours, "Is downtime required for fallback?").setHidden(false).setRequired(true)
                IssueOperations.getFormFieldByName(fieldBehaviours, "Was Fallback Plan tested?").setHidden(false).setRequired(true)
                IssueOperations.getFormFieldByName(fieldBehaviours, "Peer Reviewer").setHidden(false).setRequired(true)
            }
        }
    }

    /**
     * Change Template
     */
    public static void changeTemplateChanged(FieldBehaviours fieldBehaviours) {
        setChangeCategoryOptions(fieldBehaviours)
        setSystem(fieldBehaviours)
        setChangeApprovers(fieldBehaviours)
    }

    /**
     * Change Category (which is not used anymore as far as I can gather)
     *
     * If Change Type is 'Standard Pre-Approved Change' and the 'Change Category' changes, update the summary from
     * the Insight object.
     */
    public static void setSummary(FieldBehaviours fieldBehaviours) {
        FormField changeTypeField = IssueOperations.getFormFieldByName(fieldBehaviours, "Change type")
        if(changeTypeField.getValue() == "Standard Pre-Approved Change") {
            FormField changeCategoryField = IssueOperations.getFormFieldByName(fieldBehaviours, "Change Category")
            ObjectBean object = Insight.findObject("Standard Change", "Name", changeCategoryField.getValue().toString())
            if(object) {
                String value = Insight.readStringAttribute(object, "Summary")
                FormField summaryField = IssueOperations.getFormFieldByName(fieldBehaviours, "Summary")
                setFormValue(summaryField, value)
            }
        }
    }

    /**
     * CI-Standard-Change
     */
    public static void updateStandardChangeValues(FieldBehaviours fieldBehaviours) {
        String objectKey = IssueOperations.getFormFieldByName(fieldBehaviours, 'CI-Standard-Change').getValue()
        if(objectKey) {
            ObjectBean standardChange = Insight.findObject(objectKey)
            if(standardChange) {
                ObjectBean cabBean = Insight.readObjectAttribute(standardChange, "CAB-Board")
                FormField cabField = IssueOperations.getFormFieldByName(fieldBehaviours, 'CI-CAB-Board')
                if(cabBean) {
                    setFormValue(cabField, cabBean.getObjectKey())
                } else {
                    setFormValue(cabField, "")
                }
                setFormValue(fieldBehaviours.getFieldById("summary"), Insight.readStringAttribute(standardChange, "Name"))
            }
        }
    }

    /**
     * Users affected
     * Type of users
     * Financial transactions
     * Time needed for Fallback
     * Known scenario
     * Tests from lower environment
     * Was Fallback Plan tested?
     * Number of resources needed for implementation
     * Time needed for implementation
     * Implementation Start
     * Implementation End
     */
    public static void calculateAllFields(FieldBehaviours fieldBehaviours) {
        // If Change activity is set, those values should override the calculations
        if(!IssueOperations.getFormFieldByName(fieldBehaviours, "CI-Change Activity").getValue()) {
            calculateChangeImpact(fieldBehaviours)
            calculateEndTimeWithFallback(fieldBehaviours)
            calculateImplementationInMaintenanceWindow(fieldBehaviours)
            calculateProbabilityOfFailure(fieldBehaviours)
            calculateChangeRisk(fieldBehaviours)
            calculateChangeSubtype(fieldBehaviours)
            calculateChangeEffort(fieldBehaviours)
        }

        if(IssueOperations.getFormFieldByName(fieldBehaviours, "Change type").getValue()) {
            if(!isChangeStartDateValid(fieldBehaviours)) {
                IssueOperations.getFormFieldByName(fieldBehaviours, "Implementation Start").setError("Please review Implementation Start - lead time is not respected")
            } else {
                IssueOperations.getFormFieldByName(fieldBehaviours, "Implementation Start").clearError()
            }
        }
    }

    /**
     * CI-Change Activity
     */
    public static void updateChangeActivityValues(FieldBehaviours fieldBehaviours) {
        String objectKey = fieldBehaviours.getFieldById(fieldBehaviours.getFieldChanged()).getValue()
        if(objectKey) {
            ObjectBean changeActivity = Insight.findObject(objectKey)
            if(changeActivity) {
                logger.severe("updateChangeActivityValues(): ${changeActivity.getObjectKey()}")
                setFieldString(fieldBehaviours, "Change subtype", changeActivity)
                setFieldString(fieldBehaviours, "Change Impact", changeActivity)
                setFieldString(fieldBehaviours, "Other Systems that can be affected", changeActivity)
                setFieldStringMultiple(fieldBehaviours, "Affected countries", changeActivity)
                setFieldString(fieldBehaviours, "Change reason", changeActivity)
                setFieldString(fieldBehaviours, "Description", changeActivity)
                setFieldString(fieldBehaviours, "Summary", changeActivity)
                setFieldString(fieldBehaviours, "Requester's Company name", changeActivity)
                setFieldString(fieldBehaviours, "Users affected", changeActivity)
                setFieldStringMultiple(fieldBehaviours, "Type of users", changeActivity)
                setFieldString(fieldBehaviours, "Financial transactions", changeActivity)
                setFieldString(fieldBehaviours, "Known scenario", changeActivity)
                setFieldString(fieldBehaviours, "Data Security Officer Approval needed", changeActivity)
                setFieldString(fieldBehaviours, "Cyber Security Architect Approval needed", changeActivity)
                setFieldString(fieldBehaviours, "Number of resources needed for implementation", changeActivity)
                setFieldString(fieldBehaviours, "Time needed for implementation", changeActivity)
                setFieldString(fieldBehaviours, "Change Impact", changeActivity)
                setFieldString(fieldBehaviours, "Tests from lower environment", changeActivity)
                setFieldString(fieldBehaviours, "Tests comment", changeActivity)
                setFieldString(fieldBehaviours, "Was Fallback Plan tested?", changeActivity)
                setFieldString(fieldBehaviours, "Backup Solution", changeActivity)
                setFieldString(fieldBehaviours, "Implementation plan", changeActivity)
                setFieldString(fieldBehaviours, "Validation plan", changeActivity)
                setFieldString(fieldBehaviours, "Fallback plan", changeActivity)
                setFieldString(fieldBehaviours, "Is downtime required for fallback?", changeActivity)
                setFieldString(fieldBehaviours, "Is downtime required?", changeActivity)
                setFieldUserMultiple(fieldBehaviours, "Peer Reviewer", changeActivity)
                setFieldUserMultiple(fieldBehaviours, "IT Change Manager", changeActivity)

                FormField formField = IssueOperations.getFormFieldByName(fieldBehaviours, "CI-CAB-Board")
                ObjectBean objectBean = Insight.readObjectAttribute(changeActivity, Constants.CHANGE_ACTIVITY_FIELDS.get("CI-CAB-Board")[0])
                if(objectBean) {
                    setFormValue(formField, objectBean.getObjectKey())
                }

                objectBean = Insight.readObjectAttribute(changeActivity, "Country")
                logger.severe("Country object bean: ${objectBean}")
                if(objectBean) {
                    formField = IssueOperations.getFormFieldByName(fieldBehaviours, "Country")
                    logger.severe("Country name: ${Insight.readStringAttribute(objectBean, "Name")}")
                    String value = Insight.readStringAttribute(objectBean, "Name")
                    if(formField.getValue() != value) {
                        setFormValue(formField, value)
                    }
                }

                String hours = Insight.readStringAttribute(changeActivity, Constants.CHANGE_ACTIVITY_FIELDS.get("Time needed for Fallback")[0])
                String minutes = Insight.readStringAttribute(changeActivity, Constants.CHANGE_ACTIVITY_FIELDS.get("Time needed for Fallback")[1])

                FormField downtimeForFallbackField  = IssueOperations.getFormFieldByName(fieldBehaviours, "Time needed for Fallback")
                List<String> downtimeForFallbackValue = downtimeForFallbackField.getValue() as List<String>

                if(hours && minutes) {
                    boolean valueChanged = downtimeForFallbackValue.size() == 2 ? hours != downtimeForFallbackValue[0] && minutes != downtimeForFallbackValue[1] : true
                    if(valueChanged) {
                        IssueType issueType = issueTypeManager.getIssueTypes().find{IssueType it -> it.getName() == "Change - unified"}
                        Project project = projectManager.getProjectObjByKey("ITSD")
                        IssueContext issueContext = new IssueContextImpl(project, issueType)
                        CustomField customField = IssueOperations.getCustomFieldByName("Time needed for Fallback")
                        FieldConfig fieldConfig = customField.getRelevantConfig(issueContext)
                        Options options = optionsManager.getOptions(fieldConfig)

                        Option parentOption = options.find {it.value == hours}
                        Option childOption = parentOption?.childOptions?.find{it.value == minutes}

                        setFormValue(downtimeForFallbackField, [parentOption?.optionId, childOption?.optionId])
                    }
                }
            }
        }
        lockFields(fieldBehaviours)
    }

    /**
     * CI-CAB-Board
     */
    public static void updateCABValues(FieldBehaviours fieldBehaviours) {
        String objectKey = fieldBehaviours.getFieldById(fieldBehaviours.getFieldChanged()).getValue()
        // logger.severe("updateCABValues(): objectKey=${objectKey}")
        if(objectKey) {
            ObjectBean cabObject = Insight.findObject(objectKey)
            if(cabObject) {
                // We do not need this part, as it's already handled by the Automation Rule
                // Uncomment this, once the multiple user picker bug has been fixed

                // String changeType = IssueOperations.getFormFieldByName(fieldBehaviours, "Change type").getValue()
                // String cabMembers = ""
                // String groupName
                // if(changeType == Constants.EMERGENCY_CHANGE) {
                //     cabMembers = findGroupMembers(cabObject, "eCAB - JG")
                //     groupName = Insight.readStringAttribute(cabObject, "Manager on Duty - JG")
                // 	if(groupName) {
                // 		Collection<ApplicationUser> groupMembers = groupManager.getUsersInGroup(groupName)
                //     	setFormValue(IssueOperations.getFormFieldByName(fieldBehaviours, "Manager on duty"), groupMembers.collect{ApplicationUser user -> user.getUsername()}.toList())
                // 	}
                // } else {
                //     cabMembers = findGroupMembers(cabObject, "CAB - JG")
                // }
                // setFormValue(IssueOperations.getFormFieldByName(fieldBehaviours, "CAB Approvers (Text)"), cabMembers)
                // groupName = Insight.readStringAttribute(cabObject, "Change Process Manager - JG")
                // if(groupName) {
                // 	Collection<ApplicationUser> groupMembers = groupManager.getUsersInGroup(groupName)
                //     setFormValue(IssueOperations.getFormFieldByName(fieldBehaviours, "Change Process Manager"), groupMembers.collect{ApplicationUser user -> user.getUsername()}.toList())
                //     // IssueOperations.getFormFieldByName(fieldBehaviours, "Change Process Manager").setHelpText("Suggested Change Process Managers: " + groupMembers.collect{ApplicationUser user -> user.getUsername()}.toList())
                // }
                FormField resolverGroupField = IssueOperations.getFormFieldByName(fieldBehaviours, "Resolver Group")
                setFormValue(resolverGroupField, Insight.readStringAttribute(cabObject, "Implementation Group - JG"))
            }
        }
    }

    /*
     * Workaround for the Multiple User Picker Bug.
     */
    public static void updateCABValues(Issue issue) {
        MutableIssue mutableIssue = (MutableIssue)issue
        List<ObjectBean> cabBoardValue = IssueOperations.getFieldValueAsObjectBeans(mutableIssue, "CI-CAB-Board")
        ApplicationUser loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

        if (cabBoardValue) {
            String objectKey = cabBoardValue[0].getObjectKey()
            if(objectKey) {
                ObjectBean cabObject = Insight.findObject(objectKey)
                if(cabObject) {
                    String changeType = IssueOperations.getFieldValueAsString(mutableIssue, "Change type")
                    String cabMembers = ""
                    String groupName
                    if(changeType == Constants.EMERGENCY_CHANGE) {
                        groupName = Insight.readStringAttribute(cabObject, "Manager on Duty - JG")
                        if(groupName) {
                            Collection<ApplicationUser> groupMembers = groupManager.getUsersInGroup(groupName)
                            IssueOperations.setFieldValueUsers(mutableIssue, "Manager on duty", groupMembers.toList())
                        }
                    }
                    cabMembers = findGroupMembersFromObjects(cabObject, "CAB Members")
                    IssueOperations.setFieldValue(mutableIssue, "CAB Approvers (Text)", cabMembers)

                    groupName = Insight.readStringAttribute(cabObject, "Change Process Manager - JG")
                    if(groupName) {
                        Collection<ApplicationUser> groupMembers = groupManager.getUsersInGroup(groupName)
                        IssueOperations.setFieldValueUsers(mutableIssue, "Change Process Manager", groupMembers.toList())
                    }
                    issueManager.updateIssue(loggedInUser, mutableIssue, EventDispatchOption.ISSUE_UPDATED, false)
                }
            }
        }
    }

    private static String findGroupMembers(ObjectBean objectBean, String attributeName) {
        String groupName = Insight.readStringAttribute(objectBean, attributeName)
        if(groupName) {
            Collection<ApplicationUser> groupMembers = groupManager.getUsersInGroup(groupName)
            return groupMembers.collect{ApplicationUser user -> user.getUsername()}.join("\n")
        } else {
            return ""
        }
    }

    /*
     * Use this function if an attribute contains references to user Objects and not Jira users.
     * The function will fetch a list of referenced objects and return a string list with usernames.
     */
    public static String findGroupMembersFromObjects(ObjectBean objectBean, String attributeName) {
        List<ObjectBean> userObjectBeanList = Insight.readObjectsAttribute(objectBean, attributeName)
        if (userObjectBeanList) {
            return userObjectBeanList.collect{ ObjectBean userObjectBean ->
                ApplicationUser user = Insight.readUserAttribute(userObjectBean, "JiraUser")
                // In case User object has an empty JiraUser attribute
                if (user) {
                    user.getUsername()
                }
            }.findResults{it}.join("\n") // findResults removes empty elements from the list
        } else {
            return ""
        }
    }


    private static void setFieldString(FieldBehaviours fieldBehaviours, String fieldName, ObjectBean object) {
        logger.severe("setFieldString(): fieldName=${fieldName}")
        String value = Insight.readStringAttribute(object, Constants.CHANGE_ACTIVITY_FIELDS.get(fieldName)[0])
        FormField field = IssueOperations.getFormFieldByName(fieldBehaviours, fieldName)
        logger.severe("setFieldString(): value = ${value}, field = ${field}")
        if(value && field && value != field.getValue()) {
            setFormValue(field, value)
        }
    }

    private static void setFieldStringMultiple(FieldBehaviours fieldBehaviours, String fieldName, ObjectBean object) {
        logger.severe("setFieldStringMultiple(): fieldName=${fieldName}")
        List<String> value = Insight.readStringAttributes(object, Constants.CHANGE_ACTIVITY_FIELDS.get(fieldName)[0])
        FormField field = IssueOperations.getFormFieldByName(fieldBehaviours, fieldName)
        if(value && field && value != field.getValue()) {
            setFormValue(field, value)
        }
    }

    private static void setFieldUserMultiple(FieldBehaviours fieldBehaviours, String fieldName, ObjectBean object) {
        List<ApplicationUser> value = Insight.readUsersAttribute(object, Constants.CHANGE_ACTIVITY_FIELDS.get(fieldName)[0])
        FormField field = IssueOperations.getFormFieldByName(fieldBehaviours, fieldName)
        if(value && field && value != field.getValue()) {
            setFormValue(field, value.collect{ApplicationUser user -> user.getUsername()})
        }
    }

    private static boolean hasValue(ObjectBean object, String fieldName) {
        String attributeName = Constants.CHANGE_ACTIVITY_FIELDS.get(fieldName)[0]
        Object value = Insight.readStringAttribute(object, attributeName)
        logger.severe("hasValue(): object=${object}, fieldName=${fieldName}, attributeName=${attributeName}, value=${value}")
        return value
    }

    private static int addScore(String value, Map<String, Integer> map) {
        int result = 0
        map.each{if(it.key == value) result += it.value}
        return result
    }

    private static FieldSetting findFieldSetting(Map<String, FieldSetting> map, String changeType) {
        FieldSetting setting = map.get(changeType)
        if(!setting && changeType) {
            setting = map.get(Constants.ALL_CHANGES)
        }
        return setting
    }

    private static String findStatusName(FieldBehaviours fieldBehaviours) {
        return fieldBehaviours?.underlyingIssue?.getStatus()?.getName()
    }

    private static void updateFields(FieldBehaviours fieldBehaviours, String changeType) {
        logger.severe("updateFields()")
        IssueOperations.getFormFieldByName(fieldBehaviours, "Change type").setRequired(true)
        fieldBehaviours.getFieldById('description').setRequired(true)
        Constants.fields.each{
            Tuple2<String, Map<String, FieldSetting>> tuple ->
                FormField field = IssueOperations.getFormFieldByName(fieldBehaviours, tuple.getFirst())
                if(field) { // Fields might not appear on all screens
                    // Special non-standard rule for "Validation Status"
                    if(tuple.getFirst() == "Validation Status") {
                        if(findStatusName(fieldBehaviours) in ["Tests and Validation", "Revert", "Pending Approval - Retrospective ECAB", "Completed", "Final Review", "Closed"]) {
                            field.setHidden(false)
                            field.setRequired(true)
                        } else {
                            field.setHidden(true)
                            field.setRequired(false)
                        }
                    } else {
                        FieldSetting fieldSetting = findFieldSetting(tuple.getSecond(), changeType)
                        logger.severe("updateFields(), field name: ${tuple.getFirst()}, fieldSetting: ${fieldSetting}")
                        if(fieldSetting) {
                            switch(fieldSetting) {
                                case FieldSetting.OPTIONAL:
                                    field.setHidden(false)
                                    field.setRequired(false)
                                    break
                                case FieldSetting.MANDATORY:
                                    field.setHidden(false)
                                    field.setRequired(true)
                                    break
                                case FieldSetting.HIDDEN:
                                    field.setHidden(true)
                                    field.setRequired(false)
                                    break
                                case FieldSetting.READONLY:
                                    field.setHidden(false)
                                    field.setRequired(false)
                                    field.setReadOnly(true)
                                    break
                            }
                        } else {
                            field.setHidden(true)
                            field.setRequired(false)
                        }
                    }
                }
        }
    }

    private static boolean isFieldEmpty(FormField field) {
        Object object = field.getValue()
        if(!object) {
            return true
        }
        if(object instanceof List) {
            List list = (List)object
            if(list.size() == 0 || !list.get(0)) {
                return true
            }
        }
        return false
    }

    private static void lockFields(FieldBehaviours fieldBehaviours) {
        boolean lockFields = false
        boolean changeActivitySet = false
        ObjectBean changeActivity = null

        if(fieldBehaviours.underlyingIssue) {
            // the Change Process Manager field is a multi-user field, the string will be something like [<username>, <username>]
            String changeProcessManagerNames = IssueOperations.getFieldValueAsString(fieldBehaviours.underlyingIssue, "Change Process Manager")
            String currentUserName = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser().getName()
            String status = fieldBehaviours.underlyingIssue.getStatus().getName()
            lockFields = status != "Planning" && !changeProcessManagerNames.contains(currentUserName)
        }

        //logger.severe("lockFields(): lockFields = ${lockFields}")

        String objectKey = fieldBehaviours.getFieldById(fieldBehaviours.getFieldChanged()).getValue()
        if(objectKey) {
            changeActivity = Insight.findObject(objectKey)
            if(changeActivity) {
                changeActivitySet = true
            }
        }

        //logger.severe("lockFields(): changeActivitySet = ${changeActivitySet}")

        if(lockFields) {
            IssueOperations.getFormFieldByName(fieldBehaviours, "Change type").setReadOnly(true)
        }
        Constants.fields.each{
            Tuple2<String, Map<String, FieldSetting>> tuple ->
                FormField field = IssueOperations.getFormFieldByName(fieldBehaviours, tuple.getFirst())
                if(field) { // Fields might not appear on all screens
                    field.setReadOnly(false)
                    if(changeActivitySet && Constants.CHANGE_ACTIVITY_FIELDS.keySet().contains(tuple.getFirst()) && hasValue(changeActivity, tuple.getFirst())) {
                        field.setReadOnly(true)
                    }
                    if(lockFields) {
                        field.setReadOnly(true)
                    }

                    // Fix for ITSD-111664. This field should be editable not only by Change Process Manager
                    if(tuple.getFirst() == "Validation Status" && findStatusName(fieldBehaviours) == "Tests and Validation") {
                        field.setReadOnly(false)
                    }
                }
        }
        //logger.severe("lockFields(): done")
    }

    public static void showHideFieldsChangeType(FieldBehaviours fieldBehaviours) {
        FormField changeTypeField = IssueOperations.getFormFieldByName(fieldBehaviours, "Change type")
        String changeType = changeTypeField.getValue()
        updateFields(fieldBehaviours, changeType)
        switch(changeType) {
            case Constants.STANDARD_CHANGE:
                fieldBehaviours.getFieldByName("Summary").setReadOnly(true)
                break
            case Constants.NORMAL_CHANGE:
                fieldBehaviours.getFieldByName("Summary").setReadOnly(false)
                break
            case Constants.EXCEPTION_CHANGE:
                fieldBehaviours.getFieldByName("Summary").setReadOnly(false)
                break
            case Constants.EMERGENCY_CHANGE:
                fieldBehaviours.getFieldByName("Summary").setReadOnly(false)
                break
        }
    }

    public static void calculateChangeImpact(FieldBehaviours fieldBehaviours) {
        String downtime = IssueOperations.getFormFieldByName(fieldBehaviours, "Is downtime required?").getValue()
        String usersAffected = IssueOperations.getFormFieldByName(fieldBehaviours, "Users affected").getValue()
        String typeOfUsers = IssueOperations.getFormFieldByName(fieldBehaviours, "Type of users").getValue()
        String financialTransactions = IssueOperations.getFormFieldByName(fieldBehaviours, "Financial transactions").getValue()
        String implementationInMaintenanceWindow = IssueOperations.getFormFieldByName(fieldBehaviours, "Implementation In Maintenance Window").getValue()

        String changeImpact = calculateChangeImpactInternal(downtime, usersAffected, typeOfUsers, financialTransactions, implementationInMaintenanceWindow)
        if(changeImpact) {
            setFormValue(IssueOperations.getFormFieldByName(fieldBehaviours, "Change Impact"), changeImpact)
        }
    }

    public static void calculateChangeImpact(Issue issue) {
        String downtime = IssueOperations.getFieldValueAsString(issue, "Is downtime required?")
        String usersAffected = IssueOperations.getFieldValueAsString(issue, "Users affected")
        String typeOfUsers = IssueOperations.getFieldValueAsString(issue, "Type of users")
        String financialTransactions = IssueOperations.getFieldValueAsString(issue, "Financial transactions")
        String implementationInMaintenanceWindow = IssueOperations.getFieldValueAsString(issue, "Implementation In Maintenance Window")

        String changeImpact = calculateChangeImpactInternal(downtime, usersAffected, typeOfUsers, financialTransactions, implementationInMaintenanceWindow)
        if(changeImpact) {
            IssueOperations.setAndStoreFieldValueOption((MutableIssue)issue, "Change Impact", changeImpact)
        }
    }

    private static String calculateChangeImpactInternal(String downtime, String usersAffected, String typeOfUsers, String financialTransactions, String implementationInMaintenanceWindow) {
        int score = addScore(downtime, ["Yes" : 15])
        //implementationInMaintenanceWindow may become relevant in the future again
        //score += addScore(implementationInMaintenanceWindow, ["No" : 5])
        score += addScore(usersAffected, ["1-100" : 3, "101-10000" : 4, "10001-" : 6])
        if(typeOfUsers.contains("ISS IT")) {
            score += 3
        }
        if(typeOfUsers.contains("ISS]") || typeOfUsers.contains("ISS,")) {
            score += 4
        }
        if(typeOfUsers.contains("Customers")) {
            score += 6
        }
        score += addScore(financialTransactions, ["Yes - can be affected" : 20])

        return [19 : "Low", 28 : "Medium", 48 : "High", (Integer.MAX_VALUE) : "Critical"].find{score <= it.key}.value
    }

    public static void calculateProbabilityOfFailure(FieldBehaviours fieldBehaviours) {
        String knownScenario = IssueOperations.getFormFieldByName(fieldBehaviours, "Known scenario").getValue()
        String testsFromLowerEnvironment = IssueOperations.getFormFieldByName(fieldBehaviours, "Tests from lower environment").getValue()
        String wasFallbackPlanTested = IssueOperations.getFormFieldByName(fieldBehaviours, "Was Fallback Plan tested?").getValue()

        String result = calculateProbabilityOfFailureInternal(knownScenario, testsFromLowerEnvironment, wasFallbackPlanTested)
        if(result) {
            setFormValue(IssueOperations.getFormFieldByName(fieldBehaviours, "Probability of failure"), result)
        }
    }

    public static void calculateProbabilityOfFailure(Issue issue) {
        String knownScenario = IssueOperations.getFieldValueAsString(issue, "Known scenario")
        String testsFromLowerEnvironment = IssueOperations.getFieldValueAsString(issue, "Tests from lower environment")
        String wasFallbackPlanTested = IssueOperations.getFieldValueAsString(issue, "Was Fallback Plan tested?")

        String result = calculateProbabilityOfFailureInternal(knownScenario, testsFromLowerEnvironment, wasFallbackPlanTested)
        if(result) {
            IssueOperations.setAndStoreFieldValueOption((MutableIssue)issue, "Probability of failure", result)
        }
    }

    private static String calculateProbabilityOfFailureInternal(String knownScenario, String testsFromLowerEnvironment, String wasFallbackPlanTested) {
        int score = addScore(knownScenario, ["No" : 15, "Yes - failed last time" : 15])
        score += addScore(testsFromLowerEnvironment, ["Yes - fully tested - success" : 0,
                                                      "Yes - partially tested - success" : 10,
                                                      "No - not tested - lack of test environment / time / data /other" : 20,
                                                      "Not necessary - repeatable change, well known, simple scenario." : 0,
                                                      "Never required - simple change." : 4,
                                                      "Never required - complex change." : 10
        ])
        score += addScore(wasFallbackPlanTested, ["No - not possible to test /Simple fallback" : 5,
                                                  "No - not possible to test /Complex fallback" : 10
        ])

        return [14 : "Low", 29 : "Medium", 44 : "High", (Integer.MAX_VALUE) : "Critical"].find{score <= it.key}.value
    }

    public static void calculateChangeRisk(FieldBehaviours fieldBehaviours) {
        String changeImpact = IssueOperations.getFormFieldByName(fieldBehaviours, "Change Impact").getValue()
        String probabilityOfFailure = IssueOperations.getFormFieldByName(fieldBehaviours, "Probability of failure").getValue()

        String changeRisk = calculateChangeRiskInternal(changeImpact, probabilityOfFailure)
        logger.severe("changeRisk: " + changeRisk)
        if(changeRisk) {
            setFormValue(IssueOperations.getFormFieldByName(fieldBehaviours, "Change risk"), changeRisk)
        }
    }

    public static void calculateChangeRisk(Issue issue) {
        String changeImpact = IssueOperations.getFieldValueAsString(issue, "Change Impact")
        String probabilityOfFailure = IssueOperations.getFieldValueAsString(issue, "Probability of failure")

        String changeRisk = calculateChangeRiskInternal(changeImpact, probabilityOfFailure)
        if(changeRisk) {
            IssueOperations.setAndStoreFieldValueOption((MutableIssue)issue, "Change risk", changeRisk)
        }
    }

    private static String calculateChangeRiskInternal(String changeImpact, String probabilityOfFailure) {
        if(changeImpact == "Low") {
            switch(probabilityOfFailure) {
                case "Low":
                case "Medium":
                    return "Low"
                case "High":
                case "Critical":
                    return "Medium"
            }
        }
        if(changeImpact == "Medium") {
            switch(probabilityOfFailure) {
                case "Low":
                case "Medium":
                case "High":
                    return "Medium"
                case "Critical":
                    return "High"
            }
        }
        if(changeImpact == "High") {
            switch(probabilityOfFailure) {
                case "Low":
                    return "Medium"
                case "Medium":
                case "High":
                    return "High"
                case "Critical":
                    return "Critical"
            }
        }
        if(changeImpact == "Critical") {
            switch(probabilityOfFailure) {
                case "Low":
                    return "High"
                case "Medium":
                case "High":
                case "Critical":
                    return "Critical"
            }
        }
        return "Low"
    }

    public static void calculateChangeSubtype(FieldBehaviours fieldBehaviours) {
        String changeType = IssueOperations.getFormFieldByName(fieldBehaviours, "Change type").getValue()
        if(changeType == Constants.STANDARD_CHANGE) {
            setFormValue(IssueOperations.getFormFieldByName(fieldBehaviours, "Change subtype"), null)
        } else {
            String changeRisk = IssueOperations.getFormFieldByName(fieldBehaviours, "Change risk").getValue()
            String numberOfResourcesNeededForImplementation = IssueOperations.getFormFieldByName(fieldBehaviours, "Number of resources needed for implementation").getValue()
            String timeNeededForImplementation = IssueOperations.getFormFieldByName(fieldBehaviours, "Time needed for implementation").getValue()

            String changeSubtype = calculateChangeSubtypeInternal(changeRisk, numberOfResourcesNeededForImplementation, timeNeededForImplementation)
            if(changeSubtype) {
                setFormValue(IssueOperations.getFormFieldByName(fieldBehaviours, "Change subtype"), changeSubtype)
            }
        }
    }

    public static void calculateChangeSubtype(Issue issue) {
        String changeRisk = IssueOperations.getFieldValueAsString(issue, "Change risk")
        String numberOfResourcesNeededForImplementation = IssueOperations.getFieldValueAsString(issue, "Number of resources needed for implementation")
        String timeNeededForImplementation = IssueOperations.getFieldValueAsString(issue, "Time needed for implementation")

        String changeSubtype = calculateChangeSubtypeInternal(changeRisk, numberOfResourcesNeededForImplementation, timeNeededForImplementation)
        if(changeSubtype) {
            IssueOperations.setAndStoreFieldValueOption((MutableIssue)issue, "Change subtype", changeSubtype)
        }
    }

    private static String calculateChangeSubtypeInternal(String changeRisk, String numberOfResourcesNeededForImplementation, String timeNeededForImplementation) {
        int score = addScore(numberOfResourcesNeededForImplementation,
                ["Only one resource" : 5, "More than one resource form one team" : 7, "More than one resource from multiple teams" : 10]
        )
        score += addScore(timeNeededForImplementation,
                ["1 minute - 2 hours" : 2, "More than 2 hours / less than 8 hours" : 5, "More than 8 hours / less than 12 hours" : 7, "More than 12 hours" : 10]
        )

        if(score <= 10) {
            //effort = "Low"
            switch(changeRisk) {
                case "Low":
                    return "Minor Change"
                case "Medium":
                case "High":
                    return "Significant Change"
                case "Critical":
                    return "Major Change"
            }
        }
        if(score > 10 && score <= 15) {
            //effort = "Medium"
            switch(changeRisk) {
                case "Low":
                    return "Minor Change"
                case "Medium":
                case "High":
                    return "Significant Change"
                case "Critical":
                    return "Major Change"
            }
        }
        if(score > 15) {
            //effort = "High"
            switch(changeRisk) {
                case "Low":
                    return "Significant Change"
                case "Medium":
                case "High":
                case "Critical":
                    return "Major Change"
            }
        }
        return "Minor Change"
    }

    public static void calculateChangeEffort(FieldBehaviours fieldBehaviours) {
        String numberOfResourcesNeededForImplementation = IssueOperations.getFormFieldByName(fieldBehaviours, "Number of resources needed for implementation").getValue()
        String timeNeededForImplementation = IssueOperations.getFormFieldByName(fieldBehaviours, "Time needed for implementation").getValue()

        String changeEffort = calculateChangeEffortInternal(numberOfResourcesNeededForImplementation, timeNeededForImplementation)
        if(changeEffort) {
            setFormValue(IssueOperations.getFormFieldByName(fieldBehaviours, "Change effort"), changeEffort)
        }
    }

    public static void calculateChangeEffort(Issue issue) {
        String numberOfResourcesNeededForImplementation = IssueOperations.getFieldValueAsString(issue, "Number of resources needed for implementation")
        String timeNeededForImplementation = IssueOperations.getFieldValueAsString(issue, "Time needed for implementation")

        String changeEffort = calculateChangeEffortInternal(numberOfResourcesNeededForImplementation, timeNeededForImplementation)
        if(changeEffort) {
            IssueOperations.setAndStoreFieldValueOption((MutableIssue)issue, "Change effort", changeEffort)
        }
    }

    private static String calculateChangeEffortInternal(String numberOfResourcesNeededForImplementation, String timeNeededForImplementation) {
        int score = addScore(numberOfResourcesNeededForImplementation,
                ["Only one resource" : 5, "More than one resource form one team" : 7, "More than one resource from multiple teams" : 10]
        )
        score += addScore(timeNeededForImplementation,
                ["1 minute - 2 hours" : 2, "More than 2 hours / less than 8 hours" : 5, "More than 8 hours / less than 12 hours" : 7, "More than 12 hours" : 10]
        )

        if (score <= 10) return "Low"
        if (score >=11 && score <= 15) return "Medium"
        if (score >= 16) return "High"
    }

    public static void calculateEndTimeWithFallback(FieldBehaviours fieldBehaviours) {
        LocalDateTime plannedEndDate = IssueOperations.getFieldValueAsDate(fieldBehaviours, "Implementation End")
        String[] array = IssueOperations.getFormFieldByName(fieldBehaviours, "Time needed for Fallback").getValue() as String[]
        logger.severe("Value for 'Time needed for Fallback': " + array)
        String timeNeededForFallbackHours = array.size() > 0 ? array[0] : ""
        String timeNeededForFallbackMinutes = array.size() > 1 ? array[1] : ""

        LocalDateTime newValue = Operations.calculateEndTimeInternal(plannedEndDate, timeNeededForFallbackHours, timeNeededForFallbackMinutes)
        if(newValue) {
            setFormValue(fieldBehaviours.getFieldByName("End time with fallback"), IssueOperations.dateToString(newValue))
        }
    }

    public static void calculateEndTimeWithFallback(Issue issue) {
        LocalDateTime plannedEndDate = IssueOperations.getFieldValueAsDate(issue, "Implementation End")
        String[] array = IssueOperations.getFieldValueAsStringArray(issue, "Time needed for Fallback")
        String timeNeededForFallbackHours = array.size() > 0 ? removeText(array[0]) : ""
        String timeNeededForFallbackMinutes = array.size() > 1 ? removeText(array[1]) : ""

        LocalDateTime newValue = Operations.calculateEndTimeInternal(plannedEndDate, timeNeededForFallbackHours, timeNeededForFallbackMinutes)
        if(newValue) {
            IssueOperations.setAndStoreFieldValueDate((MutableIssue)issue, "End time with fallback", newValue)
        }
    }

    /**
     * Values of cascading fields is a 2-item array where each element is of the form XXX=<value selected>.
     * This method picks out <value selected> from an item
     */
    private static String removeText(String string) {
        int index = string.indexOf('=')
        return string.substring(index+1)
    }

    private static LocalDateTime calculateEndTimeInternal(LocalDateTime plannedEndDate, String timeNeededForFallbackHours, String timeNeededForFallbackMinutes) {
        if(plannedEndDate) {
            if(timeNeededForFallbackHours) {
                int hours = Integer.parseInt(timeNeededForFallbackHours)
                plannedEndDate = plannedEndDate.plusHours(hours)

                if(timeNeededForFallbackMinutes) {
                    int minutes = Integer.parseInt(timeNeededForFallbackMinutes)
                    plannedEndDate = plannedEndDate.plusMinutes(minutes)
                }
            }
            return plannedEndDate
        } else {
            return null
        }
    }

    public static void calculateImplementationInMaintenanceWindow(FieldBehaviours fieldBehaviours) {
        LocalDateTime plannedStartDate = IssueOperations.getFieldValueAsDate(fieldBehaviours, "Implementation Start")
        LocalDateTime endTimeWithFallback = IssueOperations.getFieldValueAsDate(fieldBehaviours, "End time with fallback")
        String system = IssueOperations.getFormFieldByName(fieldBehaviours, "System").getValue()
        logger.severe(plannedStartDate.toString())

        String result = calculateImplementationInMaintenanceWindowInternal(plannedStartDate, endTimeWithFallback, system)
        setFormValue(IssueOperations.getFormFieldByName(fieldBehaviours, "Implementation In Maintenance Window"), result)
    }


    public static void calculateImplementationInMaintenanceWindow(Issue issue) {
        LocalDateTime plannedStartDate = IssueOperations.getFieldValueAsDate(issue, "Implementation Start")
        LocalDateTime endTimeWithFallback = IssueOperations.getFieldValueAsDate(issue, "End time with fallback")
        String system = IssueOperations.getFieldValueAsString(issue, "System")

        String result = calculateImplementationInMaintenanceWindowInternal(plannedStartDate, endTimeWithFallback, system)
        IssueOperations.setAndStoreFieldValueOption((MutableIssue)issue, "Implementation In Maintenance Window", result)
    }

    private static String calculateImplementationInMaintenanceWindowInternal(LocalDateTime plannedStartDate, LocalDateTime endTimeWithFallback, String system) {
        if(!(plannedStartDate && endTimeWithFallback && system)) {
            return "No"
        }

        List<ObjectBean> objects = Insight.findObjects("Maintenance Window", "System", system)

        for(ObjectBean object : objects) {
            LocalDateTime windowStart = Insight.readDateAttribute(object, "Start time")
            LocalDateTime windowEnd = Insight.readDateAttribute(object, "End time")

            if(!(plannedStartDate.isBefore(windowStart) || endTimeWithFallback.isAfter(windowEnd))) {
                return "Yes"
            }
        }
        return "No"
    }

    /**
     * If the user has selected 'Standard Pre-Approved Change' as 'Change Type', the standard changes in Insight should
     * be shown in the 'Change Category' field.
     * For any other value, the Change Categories in Insight, where the Template attribute matches the 'Change Template'
     * field, should be shown.
     *
     * Currently not used
     */
    private static Iterable<Option> filterOptions(String changeType, String template, Options options) {
        List<ObjectBean> objects
        List<String> objectNames = []
        if(changeType == "Standard Pre-Approved Change") {
            objects = Insight.findObjects("Standard Change")
            objects.each{
                String name = Insight.readStringAttribute(it, "Name")
                objectNames.add(name)
            }
        } else {
            objects = Insight.findObjects("Change Category", "Change Template", template)
            objects.each{
                String name = Insight.readStringAttribute(it, "Display Name")
                objectNames.add(name)
            }
        }

        List<Option> result = options.findAll{Option option -> objectNames.any{option.getValue() == it}}
        return result
    }

    public static void setChangeCategoryOptions(FieldBehaviours fieldBehaviours) {
        /*FormField changeTemplateField = IssueOperations.getFormFieldByName(fieldBehaviours, "Change Template")
        FormField changeTypeField = IssueOperations.getFormFieldByName(fieldBehaviours, "Change type")
        FormField changeCategoryField = IssueOperations.getFormFieldByName(fieldBehaviours, "Change Category")

        String changeType = changeTypeField.getValue()
        String changeTemplate = changeTemplateField.getValue()

        if(changeType == "Standard Pre-Approved Change" || (changeType && changeTemplate)) {
	        CustomField customField = customFieldManager.getCustomFieldObjectsByName("Change Category")[0]
			FieldConfig config = customField.getRelevantConfig(fieldBehaviours.getIssueContext())
			Options options = optionsManager.getOptions(config)

        	changeCategoryField.setFieldOptions(filterOptions(changeType, changeTemplate, options))
        } else {
            changeCategoryField.setFieldOptions([])
        }*/
    }



    public static void setItManagerOnDuty(Issue issue) {
        String changeType = IssueOperations.getFieldValueAsString(issue, "Change type")
        if(changeType == "Emergency Change") {
            String changeTemplate = IssueOperations.getFieldValueAsString(issue, "Change Template")
            ObjectBean object = Insight.findObject("Change Template", "Name", changeTemplate)
            if(object) {
                List<ApplicationUser> users = Insight.readUsersAttribute(object, "IT Manager on duty")
                IssueOperations.setAndStoreFieldValueUser((MutableIssue)issue, "IT Manager on duty", users.size() > 0 ? users.get(0) : null)
            }
        }
    }

    public static void setSystem(FieldBehaviours fieldBehaviours) {
        FormField changeTemplateField = IssueOperations.getFormFieldByName(fieldBehaviours, "Change Template")
        FormField systemField = fieldBehaviours.getFieldByName("System")
        ObjectBean object = Insight.findObject("Change Template", "Name", changeTemplateField.getValue().toString())
        if(object) {
            String value = Insight.readStringAttribute(object, "System")
            if(value) {
                setFormValue(systemField, value)
                return
            }
        }
    }

    public static void setChangeApprovers(FieldBehaviours fieldBehaviours) {
        FormField changeTemplateField = IssueOperations.getFormFieldByName(fieldBehaviours, "Change Template")
        FormField changeApproversField = IssueOperations.getFormFieldByName(fieldBehaviours, "Change Approvers")
        ObjectBean object = Insight.findObject("Change Template", "Name", changeTemplateField.getValue().toString())
        if(object) {
            List<ApplicationUser> value = Insight.readUsersAttribute(object, "Change Approvers")
            setFormValue(changeApproversField, value.collect{ApplicationUser user -> user.getUsername()})
        } else {
            setFormValue(changeApproversField, [])
        }
    }

    public static void notifyApprovers(Issue issue) {
        List<ApplicationUser> users = IssueOperations.getFieldValueAsUserList(issue, "Change Approvers")
        users.each{ApplicationUser user ->
            Util.sendEmail(user.getEmailAddress(), "New Emergency Change", "Issue " + issue.getKey())
            ObjectBean object = Insight.findObjectInherited("Users", "JiraUser", user.getUsername())
            if(object) {
                String phoneNumber = Insight.readStringAttribute(object, "Mobile")
                int returnCode = Util.sendSms(phoneNumber, issue.getProjectObject().getKey(), "", "New emergency change: " + issue.getKey())
                if(returnCode != 200) {
                    logger.severe("Could not send SMS to " + phoneNumber + " to " + user.getUsername() + ", error: " + returnCode)
                }
            }
        }
    }

    public static boolean isChangeStartDateValid(FieldBehaviours fieldBehaviours) {
        logger.severe("isChangeStartDateValid(FieldBehaviours)")
        LocalDateTime startDate = IssueOperations.getFieldValueAsDate(fieldBehaviours, "Implementation Start")
        String changeType = fieldBehaviours.getFieldByName("Change type").getValue()
        String changeSubtype = fieldBehaviours.getFieldByName("Change subtype").getValue()
        String cabBoardKey = fieldBehaviours.getFieldByName("CI-CAB-Board").getValue()
        String alreadyImplemented = fieldBehaviours.getFieldByName("Already implemented?").getValue()
        logger.severe("isChangeStartDateValid(): Parameters: startdate = ${startDate}, changeType = ${changeType}, changeSubtype = ${changeSubtype}, cabBoardKey = ${cabBoardKey}, alreadyImplemented = ${alreadyImplemented}")

        ObjectBean cabBoard = null
        if(cabBoardKey) {
            cabBoard = Insight.findObject("CAB-Boards", cabBoardKey)
            logger.severe("isChangeStartDateValid(): cabBoard: " + cabBoard)

        }
        boolean leavingPlanning = fieldBehaviours?.underlyingIssue?.getStatus()?.getName() == "Planning"
        return isChangeStartDateValidInternal(startDate, changeType, changeSubtype, cabBoard, fieldBehaviours.underlyingIssue, alreadyImplemented, leavingPlanning)
    }

    public static boolean isChangeStartDateValid(Issue issue) {
        logger.severe("isChangeStartDateValid(Issue)")
        LocalDateTime startDate = IssueOperations.getFieldValueAsDate(issue, "Implementation Start")
        String changeType = IssueOperations.getFieldValueAsString(issue, "Change type")
        String changeSubtype = IssueOperations.getFieldValueAsString(issue, "Change subtype")
        List<ObjectBean> list = IssueOperations.getFieldValueAsObjectBeans(issue, "CI-CAB-Board")
        String alreadyImplemented = IssueOperations.getFieldValueAsString(issue, "Already implemented?")

        logger.severe("Parameters: startdate = ${startDate}, changeType = ${changeType}, changeSubtype = ${changeSubtype}, cabBoardKey = ${list?.get(0)}")

        return isChangeStartDateValidInternal(startDate, changeType, changeSubtype, list?.get(0), issue, alreadyImplemented, false)
    }

    /**
     * Monday = 0, Tuesday = 1, etc.
     */
    private static int dayToNumber(String day) {
        return ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"].indexOf(day)
    }

    private static int dayToNumber(int day) {
        return [Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY].indexOf(day)
    }

    /**
     * Calculates the number of days until the next CAB meeting
     *
     * @arg currentDay Find the first CAB meeting after or on this (week) day. Valid values: Calendar.MONDAY, Calendar.TUESDAY, etc.
     * @arg cabDay The day of a weekly CAB meeting. Valid values: "Monday", "Tuesday", etc.
     * @return The number of days to the next CAB meeting
     */
    private static int daysToCab(int currentDay, String cabDay) {
        int currentDayAsInt = dayToNumber(currentDay)
        if(cabDay == null) {
            return Integer.MAX_VALUE
        } else {
            int cabDayAsInt = dayToNumber(cabDay)
            int daysToCab = cabDayAsInt - currentDayAsInt
            if(daysToCab < 0) {
                daysToCab += 7
            }
            return daysToCab
        }
    }

    /**
     * NOT USED AT THE MOMENT
     *
     * @arg leadTime number of days needed before approval on a CAB meeting
     * @arg cab1 Weekday for the CAB meeting
     * @arg cab2 Weekday for a possible second (weekly) CAB meeting
     * @return Earliest possible date for implementation (lead time + days to next earliest CAB + 1)
     */
    private static LocalDateTime earliestValidImplementationDate(int leadTime, String cab1, String cab2) {
        logger.severe("earliestValidImplementationDate(${leadTime}, ${cab1}, ${cab2}")
        Calendar calendar = GregorianCalendar.getInstance()

        //Run a loop and check each day to skip a weekend
        for(int i = 0; i < leadTime; i++) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
            if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        int daysToCab1 = daysToCab(calendar.get(Calendar.DAY_OF_WEEK), cab1)
        int daysToCab2 = daysToCab(calendar.get(Calendar.DAY_OF_WEEK), cab2)

        if(daysToCab1 < daysToCab2) {
            calendar.add(Calendar.DAY_OF_MONTH, daysToCab1+1) // earliest possible implementation date is the day *after* the CAB, hence the '+1'
        } else {
            calendar.add(Calendar.DAY_OF_MONTH, daysToCab2+1)
        }

        calendar.clearTime()
        TimeZone tz = calendar.getTimeZone()
        ZoneId zoneId = tz.toZoneId()
        LocalDateTime localDateTime = LocalDateTime.ofInstant(calendar.toInstant(), zoneId)

        return localDateTime
    }

    private static boolean isChangeStartDateValidInternal(LocalDateTime startDate, String changeType, String changeSubtype, ObjectBean cabBoardObject, Issue issue, String alreadyImplemented, boolean leavingPlanning) {
        logger.severe("isChangeStartDateValidInternal(): changeType = ${changeType}, alreadyImplemented = ${alreadyImplemented}")
        if(!(startDate && changeType && cabBoardObject)) {
            logger.severe("isChangeStartDateValidInternal(): null-arguments")
            return false
        }
        if(changeType == Constants.STANDARD_CHANGE) {
            return startDate?.isAfter(LocalDateTime.now())
        }
        if(!changeSubtype) {
            logger.severe("isChangeStartDateValidInternal(): null-arguments(2)")
            return false
        }
        // if(!leavingPlanning) {
        //     return true
        // }

        // if this issue is not in status Planning but has been in Planning before, lead times rules do not apply
        if(issue != null && hasIssueLeftStatus(issue, "Planning")) {
            // logger.severe("isChangeStartDateValidInternal(): Returning true - is not in Planning but has been in Planning before")
            return true
        }

        if(changeType == Constants.EMERGENCY_CHANGE && alreadyImplemented == "Yes") {
            // logger.severe("isChangeStartDateValidInternal(): Returning true - retrospective change")
            return true
        }
        if(changeType != Constants.NORMAL_CHANGE) {
            // logger.severe("isChangeStartDateValidInternal(): Change is different than NORMAL_CHANGE and checking if start date is after today")
            return startDate?.isAfter(LocalDateTime.now())
        }
        int leadTime = Integer.MAX_VALUE
        switch (changeSubtype) {
            case "Minor Change":
                leadTime = 1
                break
            case "Significant Change":
                leadTime = 3
                break
            case "Major Change":
                leadTime = 5
                break
        }
        //LocalDateTime earliestDate = earliestValidImplementationDate(leadTime, scheduleDay1, scheduleDay2)
        LocalDateTime earliestDate = LocalDateTime.now().plusDays(leadTime)
        if(!earliestDate.isBefore(startDate)) {
            logger.severe("isChangeStartDateValidInternal(): false - earliest possible date is: ${earliestDate}")
            return false
        } else {
            logger.severe("isChangeStartDateValidInternal(): true - earliest possible date is: ${earliestDate}")
            return true
        }
        logger.severe("isChangeStartDateValidInternal(): No condition met")
    }

    private static void addSubTask(MutableIssue parentIssue, String summary, Collection<ApplicationUser> approvers) {
        ApplicationUser loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
        String defaultPriority = prioritySchemeManager.getDefaultOption(parentIssue)
        IssueType subTaskIssueType = constantsManager.getSubTaskIssueTypeObjects().find{IssueType issueType -> issueType.getName() == "Change - Unified - Sub-task"}
        IssueInputParameters issueInputParameters = issueService.newIssueInputParameters()
                .setProjectId(parentIssue.getProjectObject().getId())
                .setIssueTypeId(subTaskIssueType.getId())
                .setReporterId(parentIssue.getReporter().getUsername())
                .setSummary(summary)
                .setPriorityId(defaultPriority)

        CreateValidationResult validationResult = issueService.validateSubTaskCreate(loggedInUser, parentIssue.getId(), issueInputParameters)
        if(validationResult.isValid()) {
            IssueResult issueResult = issueService.create(loggedInUser, validationResult)
            if(issueResult.isValid()) {
                subTaskManager.createSubTaskIssueLink(parentIssue, issueResult.getIssue(), loggedInUser)

                IssueOperations.setAndStoreFieldValueUsers(issueResult.getIssue(), "Additional approvers", approvers.toList())
                IssueOperations.transitionIssue(issueResult.getIssue(), "Send for approval", loggedInUser)
            } else {
                logger.severe("addSubTask(): Create error ${issueResult.getErrorCollection()}")
            }
        } else {
            logger.severe("addSubTask(): Validation error ${validationResult.getErrorCollection()}")
        }
    }

    private static Tuple2<Collection<ApplicationUser>, Collection<ApplicationUser>> findApprovers(MutableIssue mutableIssue) {
        List<ObjectBean> cabList = IssueOperations.getFieldValueAsObjectBeans(mutableIssue, "CI-CAB-Board")
        Collection<ApplicationUser> dataList
        Collection<ApplicationUser> cyberList
        if(cabList && cabList[0]) {
            String groupName  = Insight.readStringAttribute(cabList[0], "Data Security Officers")
            if(groupName) {
                dataList = groupManager.getUsersInGroup(groupName)
            } else {
                dataList = new ArrayList<ApplicationUser>()
            }
            groupName = Insight.readStringAttribute(cabList[0], "Cyber Security Architects")
            if(groupName) {
                cyberList = groupManager.getUsersInGroup(groupName)
            } else {
                cyberList = new ArrayList<ApplicationUser>()
            }
        }
        return new Tuple2<Collection<ApplicationUser>, Collection<ApplicationUser>>(dataList, cyberList)
    }

    /**
     * If approval is needed from Data Security Officer and/or Cyber Security Architect, sub tasks with an approval flow
     * need to be created.
     */
    public static void handleSubTasks(Issue issue) {
        MutableIssue mutableIssue = (MutableIssue)issue
        String dataApproval = IssueOperations.getFieldValueAsString(mutableIssue, "Data Security Officer Approval needed")
        String cyberApproval = IssueOperations.getFieldValueAsString(mutableIssue, "Cyber Security Architect Approval needed")
        ApplicationUser loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
        // set to True of dataApproval or cyberApproval have changed
        boolean valueChanged = false
        // logger.severe("handleSubTasks(): dataApproval=${dataApproval}, cyberApproval=${cyberApproval}")
        Collection<ApplicationUser> currentApprovers = IssueOperations.getFieldValueAsUserList(issue, "Additional approvers")
        // This collection is used to set additionalApprovers field and give access to the parent ticket to Data and Cyber approvers
        Collection<ApplicationUser> additionalApprovers = new ArrayList<ApplicationUser>()

        Issue dataSubtask = mutableIssue.getSubTaskObjects().find{Issue sub -> sub.getSummary() == "Data Security Officer Approval"}
        Issue cyberSubtask = mutableIssue.getSubTaskObjects().find{Issue sub -> sub.getSummary() == "Cyber Security Architect Approval"}

        Tuple2<Collection<ApplicationUser>, Collection<ApplicationUser>> approvers = findApprovers(mutableIssue)

        if(dataApproval == "Yes" && !dataSubtask) {
            Collection<ApplicationUser> dataApprovers = new ArrayList<ApplicationUser>()

            if(approvers.getFirst().isEmpty()) {
                dataApprovers = [userManager.getUserByName("bo.falk@group.issworld.com")]
            } else {
                dataApprovers = approvers.getFirst()
            }

            addSubTask(mutableIssue, "Data Security Officer Approval", dataApprovers)
            additionalApprovers += dataApprovers
            valueChanged = true
        }
        if(dataApproval != "Yes" && dataSubtask) {
            issueManager.deleteIssue(loggedInUser, dataSubtask, EventDispatchOption.ISSUE_DELETED, false)
            valueChanged = true
        }
        if(cyberApproval == "Yes" && !cyberSubtask) {
            Collection<ApplicationUser> cyberApprovers = new ArrayList<ApplicationUser>()

            if(approvers.getSecond().isEmpty()) {
                cyberApprovers = [userManager.getUserByName("filip.rejch@group.issworld.com")]
            } else {
                cyberApprovers = approvers.getSecond()
            }

            addSubTask(mutableIssue, "Cyber Security Architect Approval", cyberApprovers)
            additionalApprovers += cyberApprovers
            valueChanged = true
        }

        if(cyberApproval != "Yes" && cyberSubtask) {
            issueManager.deleteIssue(loggedInUser, cyberSubtask, EventDispatchOption.ISSUE_DELETED, false)
            valueChanged = true
        }

        /*
         * Sort and remove duplicate elements from the list and update the parent ticket
         * only if current and new additional approvers differ
         */
        if (valueChanged) {
            if (currentApprovers) {
                currentApprovers.sort()
            }
            if (additionalApprovers) {
                additionalApprovers.unique().sort()
            }

            if (additionalApprovers != currentApprovers) {
                IssueOperations.setAndStoreFieldValueUsers(mutableIssue, "Additional approvers", additionalApprovers.toList())
            }
        }
    }

    public static boolean hasUnapprovedSubTasks(Issue issue) {
        Collection<Issue> subTasks = issue.getSubTaskObjects()
        return !subTasks.isEmpty() && subTasks.find{Issue subTask -> subTask.getStatus().getName() != "Done"}
    }
}
