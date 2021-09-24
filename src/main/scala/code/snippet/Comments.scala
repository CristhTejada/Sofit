/**
  * Open Bank Project - Sofi Web Application
  * Copyright (C) 2011 - 2021, TESOBE GmbH

  * This program is free software: you can redistribute it and/or modify
  * it under the terms of the GNU Affero General Public License as published by
  * the Free Software Foundation, either version 3 of the License, or
  * (at your option) any later version.

  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU Affero General Public License for more details.

  * You should have received a copy of the GNU Affero General Public License
  * along with this program.  If not, see <http://www.gnu.org/licenses/>.

  * Email: contact@tesobe.com
  * TESOBE GmbH.
  * Osloer Str. 16/17
  * Berlin 13359, Germany

  * This product includes software developed at
  * TESOBE (http://www.tesobe.com/)
  * by
  * Simon Redfern : simon AT tesobe DOT com
  * Stefan Bethge : stefan AT tesobe DOT com
  * Everett Sochowski : everett AT tesobe DOT com
  * Ayoub Benali: ayoub AT tesobe DOT com

 */
package code.snippet

import net.liftweb.http.js.JsCmds.Noop
import net.liftweb.http.Templates
import net.liftweb.util.Helpers._
import net.liftweb.http.S
import net.liftweb.common.Full
import scala.xml.NodeSeq
import net.liftweb.http.SHtml
import net.liftweb.common.Box
import net.liftweb.http.js.JsCmd
import net.liftweb.http.js.JE.JsRaw
import net.liftweb.http.js.JsCmds.RedirectTo
import net.liftweb.http.SessionVar
import scala.xml.Text
import net.liftweb.json._
import net.liftweb.json.JsonAST.JObject
import net.liftweb.json.JsonAST.JField
import net.liftweb.json.JsonAST.JString
import net.liftweb.json.JsonAST.JArray
import net.liftweb.http.StringField
import java.util.Date
import java.text.SimpleDateFormat
import code.util.Helper.MdcLoggable
import java.util.Currency
import net.liftweb.http.js.jquery.JqJsCmds.{AppendHtml,Hide}
import net.liftweb.http.js.JsCmds.{SetHtml,SetValById}
import net.liftweb.http.js.JE.Str
import net.liftweb.http.js.JsCmds.Alert
import net.liftweb.util.Props
import scala.xml.Utility
import net.liftweb.common.Failure
import java.net.URL
import java.net.URI
import java.text.NumberFormat
import net.liftweb.common.ParamFailure
import net.liftweb.util.CssSel
import code.lib.ObpJson._
import code.lib.ObpAPI
import code.lib.OAuthClient
import net.liftweb.http.RequestVar
import code.util.Helper

case class CommentsURLParams(bankId: String, accountId: String, viewId: String, transactionId: String)

/**
 * This whole class is a rather hastily put together mess
  *
  * but it does show the details of one transaction..
 */
class Comments(params : (TransactionJson, AccountJson, CommentsURLParams)) extends MdcLoggable{
  
  val FORBIDDEN = "---"
  val transactionJson = params._1
  val accountJson = params._2
  val urlParams = params._3
  val details = transactionJson.details
  val transactionMetaData = transactionJson.metadata
  val otherHolder = transactionJson.other_account.flatMap(_.holder)
  val transactionValue = details.flatMap(_.value)
  val hasManagementAccess = Helper.hasManagementAccess(accountJson)

  def calcCurrencySymbol(currencyCode: Option[String]) = {
    (for {
      code <- currencyCode
      currency <- tryo { Currency.getInstance(code) }
      symbol <- tryo { currency.getSymbol(S.locale) }
    } yield symbol) getOrElse FORBIDDEN
  }
  val commentDateFormat = new SimpleDateFormat("kk:mm:ss EEE MMM dd yyyy")
  val NOOP_SELECTOR = "#i_am_an_id_that_should_never_exist" #> ""


  def accountTitle = ".account-title *" #> Helper.getAccountTitle(accountJson)


  /*
  TODO: Is this a good name for this function?
   */
  def commentPageTitle(xhtml: NodeSeq): NodeSeq = {
    val dateFormat = new SimpleDateFormat("EEE MMM dd yyyy")
    var theCurrency = FORBIDDEN
    def formatDate(date: Box[Date]): String = {
      date match {
        case Full(d) => dateFormat.format(d)
        case _ => FORBIDDEN
      }
    }

    (
      ".amount *" #>{
        val amount : String = transactionValue.flatMap(_.amount) getOrElse FORBIDDEN
        val transactionCurrencyCode = transactionValue.flatMap(_.currency)
        val currencySymbol = calcCurrencySymbol(transactionCurrencyCode)
        //TODO: Would be nice to get localise this in terms of "." vs "," and the location of the currency symbol
        // (before or after the number)
        amount + " " + currencySymbol
      } &
      ".other_account_holder *" #> {

        def otherHolderSelector = {
          val holderName = otherHolder.flatMap(_.name)
          val isAlias = otherHolder.flatMap(_.is_alias) getOrElse true

          def aliasSelector = {

            def indicatorClass = urlParams.viewId match {
              case "public" => ".alias_indicator [class+]" #> "alias_indicator_public"
              case _ => ".alias_indicator [class+]" #> "alias_indicator_private"
            }
            
            if (isAlias) {
              ".alias_indicator *" #> "(Alias)" &
              indicatorClass
            } else {
              NOOP_SELECTOR
            }
          }
          
          ".the_name" #> holderName &
          aliasSelector
        }
        
        if(otherHolder.isDefined) otherHolderSelector
        else "* *" #> FORBIDDEN
      } &
      ".date_cleared *" #> {
        val finishDate = details.flatMap(_.completed)
        finishDate match {
          case Some(date) => formatDate(Full(date))
          case _ => FORBIDDEN
        }
      } &
        // Used for the reference / description entered by the account holder when the transaction is posted
        // In one version of the API, "label" was the name of this
        ".description *" #> {
          val description = details.flatMap(_.description)
          description.getOrElse(FORBIDDEN)
        } &
      // Narrative is part of metadata (probably entered after the transaction is posted) aka owners comment
      ".narrative *" #> {
        val narrative = transactionMetaData.flatMap(_.narrative)
        narrative.getOrElse(FORBIDDEN)
      } &
      ".new_balance *" #> {
        val newBalance = details.flatMap(_.new_balance)
        newBalance match {
          case Some(b) => {
            val amount = b.amount getOrElse FORBIDDEN
            val accountCurrencyCode = b.currency
            val currencySymbol = calcCurrencySymbol(accountCurrencyCode)
            amount + " " + currencySymbol
          }
          case _ => FORBIDDEN
        }
      }
    ).apply(xhtml)
  }

  def images = {
    addImage andThen showImages
  }

  def noImages = ".images_list" #> ""
  def imagesNotAllowed = "* *" #> ""

  def imageHtmlId(image: TransactionImageJson) : String = "trans-image-" + image.id.getOrElse("")

  def showImages = {

    def imagesSelector(imageJsons: List[TransactionImageJson]) = {

      def deleteImage(imageJson: TransactionImageJson) = {
        //TODO: This could be optimised into calling an ajax function with image id as a parameter to avoid
        //storing multiple closures server side (i.e. one client side function maps to on server side function
        //that takes a parameter)
        if (!hasManagementAccess) {
          Text("")
        } else {
          SHtml.a(() => {
            imageJson.id match {
              case Some(id) => {
                val deleted = ObpAPI.deleteImage(urlParams.bankId, urlParams.accountId,
                  urlParams.viewId, urlParams.transactionId, id)
                if (!deleted) logger.error("Tried to delete an image but it didn't work")
              }
              case _ => logger.warn("Tried to delete an image without an id")
            }
            Hide(imageJson.id.getOrElse(""))
          }, <img src="/media/images/close-icon.png" />, ("title", "Remove the image"))
        }
      }
        
      ".noImages" #> "" &
        ".image-holder" #> imageJsons.map(imageJson => {
          ".image-holder [data-id]" #> imageHtmlId(imageJson) &
          ".image-holder [id]" #> imageJson.id.getOrElse("") &
            ".trans-image [src]" #> imageJson.URL.getOrElse("") &
            ".image-description *" #> imageJson.label.getOrElse("") &
            ".postedBy *" #> imageJson.user.flatMap(_.display_name).getOrElse("") &
            ".postedTime *" #> imageJson.date.map(commentDateFormat.format(_)).getOrElse("") &
            ".deleteImage" #> deleteImage(imageJson)
        })
    }
      
    val imageJsons = transactionJson.imageJsons

    imageJsons match {
      case Some(iJsons) => {
        if(iJsons.isEmpty) noImages
        else imagesSelector(iJsons)
      }
      case _ => imagesNotAllowed
    }

  }

  def addImage = {

    // transloadit requires its parameters to be a json string, according to docs
    // but it doesn't like the output of Utility.escape and accepts the string unquoted
    val transloadItParams : String = {
      import net.liftweb.json.JsonDSL._
      import net.liftweb.json._

      val authKey = Props.get("transloadit.authkey") getOrElse ""
      val addImageTemplate = Props.get("transloadit.addImageTemplate") getOrElse ""
      val json =
        (
          "auth" -> (
            "key" -> authKey
          )
        ) ~
        ("template_id" -> addImageTemplate)
      compactRender(json)
      //Utility.escape(compact(render(json)), new StringBuilder).toString
    }

    if(S.post_?) {
      val description = S.param("description") getOrElse ""
      val addedImage = for {
        transloadit <- S.param("transloadit") ?~! "No transloadit data received"
        json <- tryo{parse(transloadit)} ?~! "Could not parse transloadit data as json"
        imageUrl <- tryo{val JString(a) = json \ "results" \ "ssl_url"; a} ?~! {"Could not extract url string from json: " + compactRender(json)}
        addedImage <- ObpAPI.addImage(urlParams.bankId, urlParams.accountId, urlParams.viewId, urlParams.transactionId, imageUrl, description)
      } yield addedImage

      addedImage match {
        case Full(added) => {
          //kind of a hack, but we redirect to a get request here so that we get the updated transaction (with the new image)
          S.redirectTo(S.uri)
        }
        case Failure(msg, _ , _) => logger.warn("Problem adding new image: " + msg)
        case _ => logger.warn("Problem adding new image")
      }
    }
    
    if (OAuthClient.loggedIn) {
      "#imageUploader [action]" #> S.uri &
        "#imageUploader" #> {
          "name=params [value]" #> transloadItParams
        }
    } else "#imageUploader" #> ""

  }

  def noTags = ".tag" #> ""
  def tagsNotAllowed = "* *" #> ""

  def deleteTag(tag: TransactionTagJson) = {
    if (!hasManagementAccess) {
      Text("")
    } else {
      SHtml.a(() => {
        tag.id match {
          case Some(id) => {
            val worked = ObpAPI.deleteTag(urlParams.bankId, urlParams.accountId, urlParams.viewId,
              urlParams.transactionId, id)
              if(!worked) logger.warn("Deleting tag with id " + id + " failed")
          }
          case _ => logger.warn("Tried to delete a tag without an id")
        }
        Hide(tag.id.getOrElse(""))
      }, <img src="/media/images/close-icon.png" />, ("title", "Remove the tag"))
    }
  }
  
  def showTags = {
    
    val tagJsons = transactionJson.tagJsons

    def showTags(tags : List[TransactionTagJson]) = {
      def orderByDateDescending = 
        (tag1: TransactionTagJson, tag2: TransactionTagJson) =>
          tag1.date.getOrElse(now).before(tag2.date.getOrElse(now))
      
      ".tagsContainer" #> {
        "#noTags" #> "" &
          ".tag" #> tags.sortWith(orderByDateDescending).map(getTagSelector)
      }
    }
    
    tagJsons match {
      case Some(tJsons) => {
        if (tJsons.isEmpty) noTags
        else showTags(tJsons)
      }
      case _ => tagsNotAllowed
    }
    
  }
  
  def getTagSelector (tag: TransactionTagJson) = {
		 ".tagID [id]" #> tag.id.getOrElse("") &
		 ".tagValue" #> tag.value.getOrElse("") &
         ".deleteTag" #> deleteTag(tag)
  }

  def addTag(xhtml: NodeSeq): NodeSeq = {

    var tagValues : List[String] = Nil
    
    def newTagsXml(newTags: List[TransactionTagJson]): Box[NodeSeq] = {
      Templates(List("templates-hidden", "_tag")).map {
        ".tag" #> newTags.map { getTagSelector }
      }
    }
    
    def addTagSelector = {
      SHtml.ajaxForm(
        SHtml.text("",
          tags => {
            val tagVals = tags.split(" ").toList.filter(tag => !tag.isEmpty)
            tagValues = tagVals
          },
          ("class", "tags-box__input"),
          ("placeholder", "Add tags seperated by spaces"),
          ("id", "addTagInput"),
          ("size", "30")) ++
          SHtml.ajaxSubmit(
            "ADD TAG",
            () => {
              val newTags = ObpAPI.addTags(urlParams.bankId, urlParams.accountId, urlParams.viewId,
                  urlParams.transactionId, tagValues)
              val newXml = newTagsXml(newTags)
              //TODO: update the page
              val content = Str("")
              SetValById("addTagInput",content)&
              SetHtml("noTags",NodeSeq.Empty) &
              AppendHtml("tags_list", newXml.getOrElse(NodeSeq.Empty))
            },
            ("id", "submitTag"),
            ("class", "comments-button tags-box--button")))
    }

    if(OAuthClient.loggedIn) {
      addTagSelector
    } else {
      (".add" #> "You need to login before you can add tags").apply(xhtml)
    }
  }

  def noComments = ".comment" #> ""
  def commentsNotAllowed = "* *" #> ""

  def showComments = {
    val commentJsons = transactionJson.commentJsons

    def showComments(comments: List[TransactionCommentJson]) = {

      def orderByDateDescending =
        (comment1: TransactionCommentJson, comment2: TransactionCommentJson) =>
          comment1.date.getOrElse(now).before(comment2.date.getOrElse(now))

      ".commentsContainer" #> {
        "#noComments" #> "" &
          ".comment" #> comments.sortWith(orderByDateDescending).zipWithIndex.map {
            case (commentJson, position) => commentCssSel(commentJson, position + 1)
          }
      }
    }
    
    commentJsons match {
      case Some(cJsons) => {
        if (cJsons.isEmpty) noComments
        else showComments(cJsons)
      }
      case _ => commentsNotAllowed
    }

  }

  def commentCssSel(commentJson: TransactionCommentJson, displayPosition : Int) = {
    def commentDate: CssSel = {
      commentJson.date.map(d => {
        ".commentDate *" #> commentDateFormat.format(d)
      }) getOrElse
        ".commentDate" #> ""
    }

    def userInfo: CssSel = {
      commentJson.user.map(u => {
        ".userInfo *" #> {
          " -- " + u.display_name.getOrElse("")
        }
      }) getOrElse
        ".userInfo" #> ""
    }

    ".text *" #> commentJson.value.getOrElse("") &
      ".commentLink * " #> { "#" + displayPosition } &
      ".commentLink [id]" #> displayPosition &
      commentDate &
      userInfo
  }
  
  var commentsListSize : Int = transactionJson.commentJsons.map(_.size).getOrElse(0)

  def addComment : CssSel = {

    //TODO: Get this from the API once there's a good way to do it
    def viewAllowsAddingComments = true

    def mustLogIn = ".add" #> "You need to login before you can submit a comment"
    
    def loggedIn : CssSel = if(viewAllowsAddingComments) addCommentSnippet
                   else ".add" #> "You cannot comment transactions on this view"

    def addCommentSnippet : CssSel = {
      var commentText = ""
      ".add" #>
	      SHtml.ajaxForm(
	        SHtml.textarea(
	          "",
	          commentText = _,
	          ("rows", "4"), ("cols", "50"),
	          ("id", "addCommentTextArea"),
              ("class", "comments-box__textarea"),
	          ("placeholder", "Type a comment here")) ++
	          SHtml.ajaxSubmit("ADD COMMENT", () => {
	            val newCommentXml = for {
	              newComment <- ObpAPI.addComment(urlParams.bankId, urlParams.accountId, urlParams.viewId, urlParams.transactionId, commentText)
	              commentXml <- Templates(List("templates-hidden", "_comment"))
	            } yield {
	              commentsListSize = commentsListSize + 1
	              commentCssSel(newComment, commentsListSize).apply(commentXml)
	            }
	            val content = Str("")
	            SetValById("addCommentTextArea", content) &
	              SetHtml("noComments", NodeSeq.Empty) &
	              AppendHtml("comment_list", newCommentXml.getOrElse(NodeSeq.Empty))
	          }, ("id", "submitComment"), ("class", "comments-button comments-box--button")))
    }

    if (!OAuthClient.loggedIn) mustLogIn
    else loggedIn
    
  }
  
}
