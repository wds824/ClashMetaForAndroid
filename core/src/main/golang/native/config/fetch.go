package config

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net/http"
	U "net/url"
	"os"
	P "path"
	"runtime"
	"strconv"
	"strings"
	"time"

	"cfa/native/app"

	"github.com/metacubex/mihomo/adapter/provider"
	clashHttp "github.com/metacubex/mihomo/component/http"
	RB "github.com/metacubex/mihomo/rules/bundle"
)

type Status struct {
	Action            string   `json:"action"`
	Args              []string `json:"args"`
	Progress          int      `json:"progress"`
	MaxProgress       int      `json:"max"`
	SubUpload         *int64   `json:"subUpload,omitempty"`
	SubDownload       *int64   `json:"subDownload,omitempty"`
	SubTotal          *int64   `json:"subTotal,omitempty"`
	SubExpire         *int64   `json:"subExpire,omitempty"`
	SubUpdateInterval *int64   `json:"subUpdateInterval,omitempty"`
	SubProfileTitle   *string  `json:"subProfileTitle,omitempty"`
}

type fetchHeader struct {
	SubscriptionUserInfo  string
	ProfileUpdateInterval string
	ProfileTitle          string
	ContentDisposition    string
}

func openUrl(ctx context.Context, url string) (io.ReadCloser, fetchHeader, error) {
	response, err := clashHttp.HttpRequest(ctx, url, http.MethodGet, http.Header{"User-Agent": {"ClashMetaForAndroid/" + app.VersionName()}}, nil)

	if err != nil {
		return nil, fetchHeader{}, err
	}

	return response.Body, fetchHeader{
		SubscriptionUserInfo:  response.Header.Get("subscription-userinfo"),
		ProfileUpdateInterval: response.Header.Get("profile-update-interval"),
		ProfileTitle:          response.Header.Get("profile-title"),
		ContentDisposition:    response.Header.Get("content-disposition"),
	}, nil
}

func openContent(url string) (io.ReadCloser, error) {
	return app.OpenContent(url)
}

func fetch(url *U.URL, file string) (fetchHeader, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	var reader io.ReadCloser
	var header fetchHeader
	var err error

	switch url.Scheme {
	case "http", "https":
		reader, header, err = openUrl(ctx, url.String())
	case "content":
		reader, err = openContent(url.String())
	default:
		err = fmt.Errorf("unsupported scheme %s of %s", url.Scheme, url)
	}

	if err != nil {
		return fetchHeader{}, err
	}

	defer reader.Close()

	return header, writeFile(file, reader)
}

func writeFile(file string, reader io.Reader) error {
	_ = os.MkdirAll(P.Dir(file), 0700)

	f, err := os.OpenFile(file, os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0600)
	if err != nil {
		return err
	}

	defer f.Close()

	_, err = io.Copy(f, reader)
	if err != nil {
		_ = os.Remove(file)
	}

	return err
}

func parseProfileUpdateInterval(value string) (int64, bool) {
	hours, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
	if err != nil {
		return 0, false
	}

	if hours <= 0 {
		return 0, true
	}

	interval := time.Duration(hours) * time.Hour
	if interval < 15*time.Minute {
		interval = 15 * time.Minute
	}

	return int64(interval / time.Millisecond), true
}

func decodeBase64Title(s string) string {
	s = strings.TrimSpace(s)
	encodings := []*base64.Encoding{
		base64.StdEncoding,
		base64.RawStdEncoding,
		base64.URLEncoding,
		base64.RawURLEncoding,
	}
	for _, enc := range encodings {
		if b, err := enc.DecodeString(s); err == nil && len(b) > 0 {
			if t := strings.TrimSpace(string(b)); t != "" {
				return t
			}
		}
	}
	return ""
}

func cleanFilenameTitle(name string) string {
	name = strings.TrimSpace(strings.Trim(name, `"'`))
	if unescaped, err := U.PathUnescape(name); err == nil && strings.TrimSpace(unescaped) != "" {
		name = strings.TrimSpace(unescaped)
	} else if unescaped, err := U.QueryUnescape(name); err == nil && strings.TrimSpace(unescaped) != "" {
		name = strings.TrimSpace(unescaped)
	}
	lower := strings.ToLower(name)
	if strings.HasSuffix(lower, ".yaml") && len(name) > 5 {
		name = strings.TrimSpace(name[:len(name)-5])
	} else if strings.HasSuffix(lower, ".yml") && len(name) > 4 {
		name = strings.TrimSpace(name[:len(name)-4])
	}
	return name
}

func parseHeaderTitle(profileTitle, contentDisposition string) string {
	pt := strings.TrimSpace(profileTitle)
	if pt != "" {
		if len(pt) >= 7 && strings.EqualFold(pt[:7], "base64:") {
			if decoded := decodeBase64Title(pt[7:]); decoded != "" {
				return decoded
			}
		}
		if unescaped, err := U.PathUnescape(pt); err == nil && strings.TrimSpace(unescaped) != "" {
			return strings.TrimSpace(unescaped)
		}
		return pt
	}

	cd := strings.TrimSpace(contentDisposition)
	if cd != "" {
		if _, params, err := mime.ParseMediaType(cd); err == nil {
			if fn := cleanFilenameTitle(params["filename"]); fn != "" {
				return fn
			}
		}

		var filenameStar, filenamePlain string
		for _, part := range strings.Split(cd, ";") {
			part = strings.TrimSpace(part)
			lowerPart := strings.ToLower(part)
			if strings.HasPrefix(lowerPart, "filename*=") {
				val := strings.TrimSpace(part[len("filename*="):])
				val = strings.Trim(val, `"'`)
				if idx := strings.Index(val, "''"); idx >= 0 {
					val = val[idx+2:]
				}
				filenameStar = cleanFilenameTitle(val)
			} else if strings.HasPrefix(lowerPart, "filename=") {
				val := strings.TrimSpace(part[len("filename="):])
				filenamePlain = cleanFilenameTitle(val)
			}
		}
		if filenameStar != "" {
			return filenameStar
		}
		if filenamePlain != "" {
			return filenamePlain
		}
	}

	return ""
}

func reportSubscriptionInfo(header fetchHeader, reportStatus func(string)) {
	userinfo := header.SubscriptionUserInfo
	updateIntervalHeader := header.ProfileUpdateInterval
	title := parseHeaderTitle(header.ProfileTitle, header.ContentDisposition)
	if userinfo == "" && updateIntervalHeader == "" && title == "" {
		return
	}

	status := Status{
		Action:      "SubscriptionInfo",
		Args:        []string{},
		Progress:    -1,
		MaxProgress: -1,
	}

	if userinfo != "" {
		info := provider.NewSubscriptionInfo(userinfo)
		expire := info.Expire * 1000
		status.SubUpload = &info.Upload
		status.SubDownload = &info.Download
		status.SubTotal = &info.Total
		status.SubExpire = &expire
	}

	if interval, ok := parseProfileUpdateInterval(updateIntervalHeader); ok {
		status.SubUpdateInterval = &interval
	}

	if title != "" {
		status.SubProfileTitle = &title
	}

	bytes, _ := json.Marshal(&status)
	reportStatus(string(bytes))
}

func FetchAndValid(
	path string,
	url string,
	force bool,
	reportStatus func(string),
) error {
	configPath := P.Join(path, "config.yaml")

	if _, err := os.Stat(configPath); os.IsNotExist(err) || force {
		url, err := U.Parse(url)
		if err != nil {
			return err
		}

		bytes, _ := json.Marshal(&Status{
			Action:      "FetchConfiguration",
			Args:        []string{url.Host},
			Progress:    -1,
			MaxProgress: -1,
		})

		reportStatus(string(bytes))

		header, err := fetch(url, configPath)
		if err != nil {
			return err
		}

		reportSubscriptionInfo(header, reportStatus)
	}

	defer runtime.GC()

	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		return err
	}

	forEachProviders(rawCfg, func(index int, total int, name string, provider map[string]any, prefix string) {
		bytes, _ := json.Marshal(&Status{
			Action:      "FetchProviders",
			Args:        []string{name},
			Progress:    index,
			MaxProgress: total,
		})

		reportStatus(string(bytes))

		u, uok := provider["url"]
		p, pok := provider["path"]

		if !uok || !pok {
			return
		}

		us, uok := u.(string)
		ps, pok := p.(string)

		if !uok || !pok {
			return
		}

		if _, err := os.Stat(ps); err == nil {
			return
		}

		url, err := U.Parse(us)
		if err != nil {
			return
		}

		if prefix == RULES {
			if pib, uok := provider["path-in-bundle"]; uok {
				if pib, uok := pib.(string); uok && pib != "" {
					// actually, we don't need to extract the file here; the core will do it.
					// however, due to historical reasons, CMFA fetches provider content when loading profile,
					// so we maintain consistency with the old behavior.
					if file, err := RB.Open(pib); err == nil {
						defer file.Close()
						if err := writeFile(ps, file); err == nil {
							return
						}
					}
				}
			}
		}

		_, _ = fetch(url, ps)
	})

	bytes, _ := json.Marshal(&Status{
		Action:      "Verifying",
		Args:        []string{},
		Progress:    0xffff,
		MaxProgress: 0xffff,
	})

	reportStatus(string(bytes))

	cfg, err := Parse(rawCfg)
	if err != nil {
		return err
	}

	destroyProviders(cfg)

	return nil
}
