package sevts.terminal.acl

import java.util.UUID
import sevts.server.domain.{FormData, Id}
import upickle.default.{macroRW, ReadWriter => RW}


package object domain {

  case class FormDataRecord(code: String,
                            ruleId: UUID,
                            formData: Id[FormData],
                            badgeData: Id[FormData]) extends Serializable
  object FormDataRecord { implicit def rw: RW[FormDataRecord] = macroRW }

  case class BadgeRecord(code: String,
                         ruleId: UUID,
                         formData: Id[FormData],
                         badgeData: Id[FormData]) extends Serializable
  object BadgeRecord { implicit def rw: RW[BadgeRecord] = macroRW }

}
